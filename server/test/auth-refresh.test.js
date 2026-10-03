/**
 * Refresh-token lifecycle: opaque tokens stored as SHA-256, one-time-use
 * rotation, family revocation on reuse (theft detection), expiry, and
 * idempotent logout.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { startServer, postJson, request, registerUser } from './helpers.js';

const HEX64 = /^[0-9a-f]{64}$/;

async function readTokenStore(server) {
  const raw = await fs.readFile(path.join(server.dataDir, 'refresh-tokens.json'), 'utf8');
  return { raw, data: JSON.parse(raw) };
}

test('refresh tokens are stored only as SHA-256 hex (never in plaintext)', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'tokuser' });
  const { raw, data } = await readTokenStore(server);

  const hashes = Object.keys(data.tokens);
  assert.equal(hashes.length, 1);
  assert.ok(HEX64.test(hashes[0]), 'storage keys must be 64-char sha256 hex');
  assert.ok(!raw.includes(registered.tokens.refreshToken), 'raw refresh token must not be on disk');

  const crypto = await import('node:crypto');
  const digest = crypto.createHash('sha256').update(registered.tokens.refreshToken).digest('hex');
  assert.equal(digest, hashes[0], 'stored key must be the SHA-256 of the raw token');
});

test('rotation: refresh returns a new pair, old token becomes one-time-use invalid', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'rotuser' });
  const first = registered.tokens.refreshToken;

  const rotated = await postJson(server.baseUrl, '/auth/refresh', { refreshToken: first });
  assert.equal(rotated.status, 200);
  assert.deepEqual(Object.keys(rotated.json), ['tokens']);
  assert.deepEqual(Object.keys(rotated.json.tokens).sort(), [
    'accessToken',
    'accessTokenExpiresIn',
    'refreshToken',
    'refreshTokenExpiresIn',
  ]);
  const second = rotated.json.tokens.refreshToken;
  assert.notEqual(second, first, 'rotation must issue a fresh refresh token');
  // Note: access tokens minted for the same subject inside the same second
  // are legitimately byte-identical (same claims, same secret), so we only
  // check that the rotated access token is usable below.

  // The rotated access token works.
  const me = await request(server.baseUrl, '/auth/me', { token: rotated.json.tokens.accessToken });
  assert.equal(me.status, 200);
  assert.equal(me.json.account.username, 'rotuser');

  // Presenting the OLD (already used) token is rejected...
  const reuse = await postJson(server.baseUrl, '/auth/refresh', { refreshToken: first });
  assert.equal(reuse.status, 401);
  assert.equal(reuse.json.error.code, 'invalid_session');

  // ...and revokes the whole family: the newest token dies too (theft detection).
  const familyKilled = await postJson(server.baseUrl, '/auth/refresh', { refreshToken: second });
  assert.equal(familyKilled.status, 401);
  assert.equal(familyKilled.json.error.code, 'invalid_session');

  const { data } = await readTokenStore(server);
  const records = Object.values(data.tokens);
  assert.ok(records.length >= 2);
  assert.ok(
    records.every((r) => r.revoked === true),
    'every token in the family must be revoked after reuse is detected',
  );
});

test('expired refresh token -> 401 invalid_session', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'oldtimer' });

  // Force expiry by rewriting expiresAt on disk.
  const storePath = path.join(server.dataDir, 'refresh-tokens.json');
  const data = JSON.parse(await fs.readFile(storePath, 'utf8'));
  for (const record of Object.values(data.tokens)) record.expiresAt = 1;
  await fs.writeFile(storePath, JSON.stringify(data), 'utf8');

  const res = await postJson(server.baseUrl, '/auth/refresh', {
    refreshToken: registered.tokens.refreshToken,
  });
  assert.equal(res.status, 401);
  assert.equal(res.json.error.code, 'invalid_session');
});

test('refresh with an unknown or missing token is rejected', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const unknown = await postJson(server.baseUrl, '/auth/refresh', {
    refreshToken: 'definitely-not-a-real-token',
  });
  assert.equal(unknown.status, 401);
  assert.equal(unknown.json.error.code, 'invalid_session');

  const missing = await postJson(server.baseUrl, '/auth/refresh', {});
  assert.equal(missing.status, 400);
  assert.equal(missing.json.error.code, 'invalid_input');
});

test('logout is idempotent 204 and kills the refresh token', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'logoutter' });
  const token = registered.tokens.refreshToken;

  const loggedOut = await postJson(server.baseUrl, '/auth/logout', { refreshToken: token });
  assert.equal(loggedOut.status, 204);
  assert.equal(loggedOut.text, '');

  // Unknown token: identical 204 (reveals nothing).
  const unknown = await postJson(server.baseUrl, '/auth/logout', { refreshToken: 'who-am-i' });
  assert.equal(unknown.status, 204);

  // No body at all: still 204.
  const noBody = await postJson(server.baseUrl, '/auth/logout', {});
  assert.equal(noBody.status, 204);

  // After logout the token can no longer be refreshed.
  const refreshAfter = await postJson(server.baseUrl, '/auth/refresh', { refreshToken: token });
  assert.equal(refreshAfter.status, 401);
  assert.equal(refreshAfter.json.error.code, 'invalid_session');
});

test('access tokens are rejected when signed with the wrong secret or wrong typ', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const jwt = (await import('jsonwebtoken')).default;
  const wrongSecret = jwt.sign({ sub: 'someone', typ: 'access' }, 'a-different-secret-that-is-long-enough-1234', {
    algorithm: 'HS256',
    expiresIn: 900,
  });
  const notAccess = jwt.sign({ sub: 'someone', typ: 'refresh' }, server.config.JWT_SECRET, {
    algorithm: 'HS256',
    expiresIn: 900,
  });

  for (const token of [wrongSecret, notAccess]) {
    const res = await request(server.baseUrl, '/auth/me', { token });
    assert.equal(res.status, 401);
    assert.equal(res.json.error.code, 'unauthorized');
  }

  let noneAlgorithm = null;
  try {
    noneAlgorithm = jwt.sign({ sub: 'someone', typ: 'access' }, '', { algorithm: 'none', expiresIn: 900 });
  } catch {
    // jsonwebtoken may refuse to sign alg:none at all — equally safe.
  }
  if (noneAlgorithm) {
    const algNone = await request(server.baseUrl, '/auth/me', { token: noneAlgorithm });
    assert.equal(algNone.status, 401);
  }
});
