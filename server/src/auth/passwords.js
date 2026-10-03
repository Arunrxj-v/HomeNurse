/**
 * Password hashing + password policy.
 *
 * Primary backend: argon2id with OWASP-recommended parameters
 *   m = 19456 (19 MiB), t = 2, p = 1, random 16-byte salt, encoded string stored.
 *
 * Fallback chain (used only if the argon2 native module cannot be installed):
 *   1. argon2 (native, preferred)
 *   2. @node-rs/argon2  (Rust prebuilt, add to deps if argon2 fails to build)
 *   3. hash-wasm        (pure-JS/WASM argon2id)
 *   4. node:crypto scrypt, documented parameters:
 *      N=32768, r=8, p=1, keylen=64, 16-byte salt, maxmem=96MiB
 *      (encoded as `scrypt$N=32768,r=8,p=1$<salt-b64>$<hash-b64>`)
 *
 * verify() dispatches on the stored string's prefix, so hashes written by one
 * backend keep verifying even if the active backend changes.
 */
import crypto from 'node:crypto';
import { HttpError } from '../util/http.js';

export const ARGON2ID_PARAMS = Object.freeze({
  type: 'argon2id',
  memoryCost: 19456, // 19 MiB
  timeCost: 2,
  parallelism: 1,
  saltLength: 16,
});

export const SCRYPT_PARAMS = Object.freeze({
  N: 32768,
  r: 8,
  p: 1,
  keylen: 64,
  saltBytes: 16,
  maxmem: 96 * 1024 * 1024,
});

/** Encoded scrypt string: scrypt$N=..,r=..,p=..$<salt-b64>$<hash-b64> */
export async function scryptHash(plain) {
  const salt = crypto.randomBytes(SCRYPT_PARAMS.saltBytes);
  const derived = await scryptDerive(plain, salt, SCRYPT_PARAMS);
  return `scrypt$N=${SCRYPT_PARAMS.N},r=${SCRYPT_PARAMS.r},p=${SCRYPT_PARAMS.p}$${salt.toString('base64')}$${derived.toString('base64')}`;
}

function scryptDerive(plain, salt, params, overrides = {}) {
  const { N, r, p, keylen, maxmem } = { ...params, ...overrides };
  return new Promise((resolve, reject) => {
    crypto.scrypt(plain, salt, keylen, { N, r, p, maxmem }, (err, key) => (err ? reject(err) : resolve(key)));
  });
}

export async function scryptVerify(encoded, plain) {
  const match = /^scrypt\$N=(\d+),r=(\d+),p=(\d+)\$([^$]+)\$([^$]+)$/.exec(encoded);
  if (!match) return false;
  const [, N, r, p, saltB64, hashB64] = match;
  const salt = Buffer.from(saltB64, 'base64');
  const expected = Buffer.from(hashB64, 'base64');
  // Recompute with the params embedded in the stored string, but bound memory.
  const derived = await scryptDerive(plain, salt, SCRYPT_PARAMS, {
    N: Number(N),
    r: Number(r),
    p: Number(p),
    maxmem: Math.max(SCRYPT_PARAMS.maxmem, 128 * Number(N) * Number(r) * 2),
  });
  if (derived.length !== expected.length) return false;
  return crypto.timingSafeEqual(derived, expected);
}

const backends = {
  async argon2() {
    const mod = await import('argon2');
    const argon2 = mod.default ?? mod;
    return {
      name: 'argon2',
      async hash(plain) {
        return argon2.hash(plain, {
          type: argon2.argon2id,
          memoryCost: ARGON2ID_PARAMS.memoryCost,
          timeCost: ARGON2ID_PARAMS.timeCost,
          parallelism: ARGON2ID_PARAMS.parallelism,
          saltLength: ARGON2ID_PARAMS.saltLength,
        });
      },
      async verify(encoded, plain) {
        try {
          return await argon2.verify(encoded, plain);
        } catch {
          return false;
        }
      },
    };
  },

  async nodeRsArgon2() {
    const mod = await import('@node-rs/argon2');
    const argon2 = mod.default ?? mod;
    const Algorithm = argon2.Algorithm ?? mod.Algorithm;
    const Argon2id = typeof Algorithm === 'object' ? Algorithm.Argon2id : 2;
    return {
      name: '@node-rs/argon2',
      async hash(plain) {
        return argon2.hash(plain, {
          algorithm: Argon2id,
          memoryCost: ARGON2ID_PARAMS.memoryCost,
          timeCost: ARGON2ID_PARAMS.timeCost,
          parallelism: ARGON2ID_PARAMS.parallelism,
          saltLength: ARGON2ID_PARAMS.saltLength,
        });
      },
      async verify(encoded, plain) {
        try {
          return await argon2.verify(encoded, plain);
        } catch {
          return false;
        }
      },
    };
  },

  async hashWasm() {
    const mod = await import('hash-wasm');
    const { argon2id } = mod;
    if (typeof argon2id !== 'function') throw new Error('hash-wasm argon2id unavailable');
    const m = ARGON2ID_PARAMS.memoryCost;
    const t = ARGON2ID_PARAMS.timeCost;
    const p = ARGON2ID_PARAMS.parallelism;
    return {
      name: 'hash-wasm',
      async hash(plain) {
        const salt = crypto.randomBytes(ARGON2ID_PARAMS.saltLength);
        const digestHex = await argon2id({
          password: plain,
          salt,
          parallelism: p,
          iterations: t,
          memorySize: m,
          hashLength: 32,
          outputType: 'hex',
        });
        return `hash-wasm$argon2id$m=${m},t=${t},p=${p}$${salt.toString('hex')}$${digestHex}`;
      },
      async verify(encoded, plain) {
        const match = /^hash-wasm\$argon2id\$m=(\d+),t=(\d+),p=(\d+)\$([0-9a-f]+)\$([0-9a-f]+)$/.exec(encoded);
        if (!match) return false;
        const [, mem, iterations, parallelism, saltHex, digestHex] = match;
        const digest = await argon2id({
          password: plain,
          salt: Buffer.from(saltHex, 'hex'),
          parallelism: Number(parallelism),
          iterations: Number(iterations),
          memorySize: Number(mem),
          hashLength: digestHex.length / 2,
          outputType: 'hex',
        });
        const a = Buffer.from(digest, 'hex');
        const b = Buffer.from(digestHex, 'hex');
        return a.length === b.length && crypto.timingSafeEqual(a, b);
      },
    };
  },

  async scrypt() {
    return { name: 'node:crypto-scrypt', hash: scryptHash, verify: scryptVerify };
  },
};

const CHAIN = ['argon2', 'nodeRsArgon2', 'hashWasm', 'scrypt'];

let backendPromise = null;

/** Resolve (memoized) the active hashing backend, walking the fallback chain. */
export function getPasswordBackend() {
  if (!backendPromise) {
    backendPromise = (async () => {
      const failures = [];
      for (const key of CHAIN) {
        try {
          return await backends[key]();
        } catch (err) {
          failures.push(`${key}: ${err.message}`);
        }
      }
      throw new Error(`No password hashing backend available (${failures.join('; ')})`);
      // istanbul ignore next -- scrypt is a node builtin and cannot realistically fail
    })();
  }
  return backendPromise;
}

/** Test/reset helper: forget the memoized backend. */
export function resetPasswordBackendForTests() {
  backendPromise = null;
}

export async function hashPassword(plain) {
  const backend = await getPasswordBackend();
  return backend.hash(plain);
}

export async function verifyPassword(stored, plain) {
  if (typeof stored !== 'string' || stored.length === 0) return false;
  if (typeof plain !== 'string') return false;
  if (stored.startsWith('scrypt$')) return scryptVerify(stored, plain);
  if (stored.startsWith('hash-wasm$')) {
    try {
      return (await backends.hashWasm()).verify(stored, plain);
    } catch {
      return false;
    }
  }
  const backend = await getPasswordBackend();
  try {
    return await backend.verify(stored, plain);
  } catch {
    return false;
  }
}

/**
 * Password policy:
 *  - at least 8 characters
 *  - not only whitespace
 *  - not equal to the username or the email (case-insensitive)
 * Throws 400 `weak_password` when violated.
 */
export function assertPasswordPolicy(password, { username, email } = {}) {
  if (typeof password !== 'string' || password.length < 8 || password.trim().length === 0) {
    throw new HttpError(400, 'weak_password', 'Password must be at least 8 characters and cannot be only whitespace.');
  }
  const lower = password.toLowerCase();
  const uname = typeof username === 'string' ? username.trim().toLowerCase() : '';
  const mail = typeof email === 'string' ? email.trim().toLowerCase() : '';
  if (uname && lower === uname) {
    throw new HttpError(400, 'weak_password', 'Password must not be the same as your username.');
  }
  if (mail && (lower === mail || lower === mail.split('@')[0])) {
    throw new HttpError(400, 'weak_password', 'Password must not be the same as your email address.');
  }
  return true;
}

/**
 * A throwaway hash used to equalize login timing when the account does not
 * exist (no user enumeration through response timing).
 */
let dummyHashPromise = null;
export async function dummyVerify(plain) {
  if (!dummyHashPromise) dummyHashPromise = hashPassword('timing-equalizer-not-a-real-password');
  const stored = await dummyHashPromise;
  try {
    await verifyPassword(stored, plain);
  } catch {
    /* ignore */
  }
}
