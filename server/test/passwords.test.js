/**
 * Password hashing unit tests: argon2id parameters, verify semantics,
 * policy rules, and the node:crypto scrypt last-resort fallback backend.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  hashPassword,
  verifyPassword,
  assertPasswordPolicy,
  getPasswordBackend,
  scryptHash,
  scryptVerify,
  dummyVerify,
  ARGON2ID_PARAMS,
  SCRYPT_PARAMS,
} from '../src/auth/passwords.js';

test('argon2id: stored hash never contains the plaintext and only the right password verifies', async () => {
  const backend = await getPasswordBackend();
  assert.equal(backend.name, 'argon2', 'argon2 native module should be the active backend');

  const plain = 'CorrectHorseBattery42';
  const stored = await hashPassword(plain);

  assert.ok(!stored.includes(plain), 'hash must not contain the plaintext');
  assert.ok(stored.startsWith('$argon2id$'), `expected an argon2id encoded hash, got: ${stored}`);
  assert.match(
    stored,
    /^\$argon2id\$v=19\$m=19456,t=2,p=1\$/,
    'must use OWASP argon2id parameters m=19456 t=2 p=1',
  );
  assert.equal(ARGON2ID_PARAMS.memoryCost, 19456);
  assert.equal(ARGON2ID_PARAMS.timeCost, 2);
  assert.equal(ARGON2ID_PARAMS.parallelism, 1);
  assert.equal(ARGON2ID_PARAMS.saltLength, 16);

  assert.equal(await verifyPassword(stored, plain), true);
  assert.equal(await verifyPassword(stored, `${plain}!`), false);
  assert.equal(await verifyPassword(stored, ''), false);
  assert.equal(await verifyPassword(stored, 'a-different-password'), false);

  // Two hashes of the same password differ (random salt).
  const second = await hashPassword(plain);
  assert.notEqual(stored, second);
  assert.equal(await verifyPassword(second, plain), true);

  // Garbage inputs never throw and never verify.
  assert.equal(await verifyPassword('not-a-hash', plain), false);
  assert.equal(await verifyPassword(null, plain), false);
  assert.equal(await verifyPassword('', plain), false);
});

test('password policy: length, whitespace-only, username and email equality', () => {
  assertPasswordPolicy('longenough1', { username: 'alice', email: 'alice@example.com' });
  assertPasswordPolicy('alice12345', { username: 'alice', email: 'alice@example.com' });

  const weak = (fn) => {
    assert.throws(fn, (err) => err.status === 400 && err.code === 'weak_password');
  };

  weak(() => assertPasswordPolicy('short7', { username: 'bob', email: 'bob@x.co' })); // 6 chars
  weak(() => assertPasswordPolicy('1234567', { username: 'bob', email: 'bob@x.co' })); // 7 chars
  weak(() => assertPasswordPolicy('        ', { username: 'bob', email: 'bob@x.co' })); // whitespace only
  weak(() => assertPasswordPolicy('', { username: 'bob', email: 'bob@x.co' }));
  weak(() => assertPasswordPolicy(undefined, { username: 'bob', email: 'bob@x.co' }));
  weak(() => assertPasswordPolicy('bob12345', { username: 'bob12345', email: 'bob@x.co' })); // = username
  weak(() => assertPasswordPolicy('BOB12345', { username: 'bob12345', email: 'bob@x.co' })); // = username (case)
  weak(() => assertPasswordPolicy('bob@x.co', { username: 'bob', email: 'bob@x.co' })); // = email
});

test('scrypt last-resort backend round-trips and rejects wrong passwords', async () => {
  const stored = await scryptHash('FallbackPassword9');
  assert.ok(stored.startsWith('scrypt$'), `unexpected encoding: ${stored}`);
  assert.equal(SCRYPT_PARAMS.N, 32768);
  assert.equal(SCRYPT_PARAMS.r, 8);
  assert.equal(SCRYPT_PARAMS.p, 1);
  assert.equal(SCRYPT_PARAMS.keylen, 64);

  assert.equal(await scryptVerify(stored, 'FallbackPassword9'), true);
  assert.equal(await scryptVerify(stored, 'wrong-password'), false);
  assert.equal(await scryptVerify('garbage', 'FallbackPassword9'), false);

  // verifyPassword dispatches on the stored prefix regardless of active backend.
  assert.equal(await verifyPassword(stored, 'FallbackPassword9'), true);
  assert.equal(await verifyPassword(stored, 'wrong-password'), false);
});

test('dummyVerify performs a hash comparison for unknown accounts without throwing', async () => {
  await dummyVerify('any-password-here');
  await dummyVerify('');
  await dummyVerify('x'.repeat(300));
});
