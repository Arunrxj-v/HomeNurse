/**
 * Token issuing / verification / rotation.
 *
 * - Access tokens: short-lived JWT (HS256) with claims { sub, typ:"access" }.
 *   Nothing else (no email, username, or medical info) goes into the claims.
 * - Refresh tokens: opaque 32-byte base64url random strings. Only their
 *   SHA-256 hex digest is stored (never the raw token). Rotation is one-time
 *   use; presenting a revoked/already-used token revokes the whole token
 *   family (refresh-token theft detection).
 * - Password-reset tokens: opaque 32-byte base64url random strings, SHA-256
 *   at rest, single-use, TTL = RESET_TOKEN_TTL (default 30 minutes).
 */
import crypto from 'node:crypto';
import jwt from 'jsonwebtoken';
import { HttpError } from '../util/http.js';

const UNAUTHORIZED = new HttpError(401, 'unauthorized', 'Authentication required.');
const INVALID_SESSION = new HttpError(401, 'invalid_session', 'Session is invalid or has expired.');
const INVALID_RESET_TOKEN = new HttpError(400, 'invalid_token', 'Password reset token is invalid or has expired.');

export function sha256hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function randomToken() {
  return crypto.randomBytes(32).toString('base64url');
}

/**
 * @param {{config: object, refreshStore: ReturnType<import('../util/jsonStore.js').createJsonStore>,
 *          resetStore: ReturnType<import('../util/jsonStore.js').createJsonStore>,
 *          now?: () => number}} deps
 */
export function createTokenManager({ config, refreshStore, resetStore, now = () => Date.now() }) {
  function issueAccess(userId) {
    return jwt.sign({ sub: userId, typ: 'access' }, config.JWT_SECRET, {
      algorithm: 'HS256',
      expiresIn: config.ACCESS_TOKEN_TTL,
    });
  }

  function verifyAccess(token) {
    let payload;
    try {
      payload = jwt.verify(token, config.JWT_SECRET, { algorithms: ['HS256'] });
    } catch {
      throw UNAUTHORIZED;
    }
    if (!payload || typeof payload.sub !== 'string' || !payload.sub || payload.typ !== 'access') {
      throw UNAUTHORIZED;
    }
    return payload;
  }

  async function createSession(userId, familyId = crypto.randomUUID()) {
    const raw = randomToken();
    const hash = sha256hex(raw);
    const expiresAt = now() + config.REFRESH_TOKEN_TTL * 1000;
    await refreshStore.update((data) => {
      data.tokens ??= {};
      data.tokens[hash] = { userId, familyId, createdAt: now(), expiresAt, revoked: false };
    });
    return { raw, familyId, expiresAt };
  }

  function revokeFamily(familyId, data) {
    for (const record of Object.values(data.tokens ?? {})) {
      if (record.familyId === familyId) {
        record.revoked = true;
        record.revokedAt = now();
      }
    }
  }

  /** Rotate a refresh token: one-time use with family revocation on reuse. */
  async function rotate(raw) {
    const hash = sha256hex(raw);
    let result = null;
    await refreshStore.update((data) => {
      data.tokens ??= {};
      const record = data.tokens[hash];
      if (!record) {
        // Unknown token: nothing to revoke (no family information), reject.
        result = { kind: 'unknown' };
        return;
      }
      if (record.revoked) {
        // Reuse of an already-used/revoked token => assume theft, kill the family.
        revokeFamily(record.familyId, data);
        result = { kind: 'reused' };
        return;
      }
      if (now() >= record.expiresAt) {
        revokeFamily(record.familyId, data);
        result = { kind: 'expired' };
        return;
      }
      const newRaw = randomToken();
      const newHash = sha256hex(newRaw);
      record.revoked = true;
      record.revokedAt = now();
      record.replacedBy = newHash;
      data.tokens[newHash] = {
        userId: record.userId,
        familyId: record.familyId,
        createdAt: now(),
        expiresAt: now() + config.REFRESH_TOKEN_TTL * 1000,
        revoked: false,
      };
      result = { kind: 'ok', userId: record.userId, familyId: record.familyId, refreshToken: newRaw };
    });
    if (!result || result.kind !== 'ok') throw INVALID_SESSION;
    return { userId: result.userId, familyId: result.familyId, refreshToken: result.refreshToken };
  }

  /** Idempotent revoke: unknown tokens are indistinguishable from known ones. */
  async function revoke(raw) {
    if (typeof raw !== 'string' || raw.length === 0) return;
    const hash = sha256hex(raw);
    await refreshStore.update((data) => {
      data.tokens ??= {};
      const record = data.tokens[hash];
      if (record && !record.revoked) {
        record.revoked = true;
        record.revokedAt = now();
      }
    });
  }

  /** Revoke every refresh token a user has (e.g. after a password reset). */
  async function revokeUserSessions(userId) {
    await refreshStore.update((data) => {
      data.tokens ??= {};
      for (const record of Object.values(data.tokens)) {
        if (record.userId === userId) {
          record.revoked = true;
          record.revokedAt = now();
        }
      }
    });
  }

  /** Physically delete every token record owned by a user (account deletion). */
  async function purgeUserTokens(userId) {
    await refreshStore.update((data) => {
      data.tokens ??= {};
      for (const [hash, record] of Object.entries(data.tokens)) {
        if (record.userId === userId) delete data.tokens[hash];
      }
    });
    await resetStore.update((data) => {
      data.tokens ??= {};
      for (const [hash, record] of Object.entries(data.tokens)) {
        if (record.userId === userId) delete data.tokens[hash];
      }
    });
  }

  async function issueResetToken(userId) {
    const raw = randomToken();
    const hash = sha256hex(raw);
    const expiresAt = now() + config.RESET_TOKEN_TTL * 1000;
    await resetStore.update((data) => {
      data.tokens ??= {};
      data.tokens[hash] = { userId, createdAt: now(), expiresAt };
    });
    return { raw, expiresAt };
  }

  /** Validate without consuming (used to check password policy first). */
  async function peekResetToken(raw) {
    if (typeof raw !== 'string' || raw.length === 0) throw INVALID_RESET_TOKEN;
    const hash = sha256hex(raw);
    const data = await resetStore.read();
    const record = data.tokens?.[hash];
    if (!record || now() >= record.expiresAt) throw INVALID_RESET_TOKEN;
    return record.userId;
  }

  /** Single-use: the record is deleted as soon as it is consumed. */
  async function consumeResetToken(raw) {
    if (typeof raw !== 'string' || raw.length === 0) throw INVALID_RESET_TOKEN;
    const hash = sha256hex(raw);
    let userId = null;
    await resetStore.update((data) => {
      data.tokens ??= {};
      const record = data.tokens[hash];
      if (!record || now() >= record.expiresAt) {
        if (record) delete data.tokens[hash]; // expired: burn it
        userId = null;
        return;
      }
      delete data.tokens[hash];
      userId = record.userId;
    });
    if (!userId) throw INVALID_RESET_TOKEN;
    return userId;
  }

  return {
    issueAccess,
    verifyAccess,
    createSession,
    rotate,
    revoke,
    revokeUserSessions,
    purgeUserTokens,
    issueResetToken,
    peekResetToken,
    consumeResetToken,
    errors: { UNAUTHORIZED, INVALID_SESSION, INVALID_RESET_TOKEN },
  };
}
