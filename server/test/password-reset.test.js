/**
 * Password reset: always-202 forgot-password, single-use + expiring reset
 * tokens (SHA-256 at rest), session revocation on success, and the
 * DEBUG_RESET_TOKENS logging gate (never in production).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { startServer, postJson, request, registerUser } from './helpers.js';

const TOKEN_RE = /token=([A-Za-z0-9_-]{20,})/;

function extractTokens(logs) {
  return logs.flatMap((line) => {
    const match = TOKEN_RE.exec(line);
    return match ? [match[1]] : [];
  });
}

async function captureResetToken(server, email) {
  const before = server.logs.length;
  const res = await postJson(server.baseUrl, '/auth/forgot-password', { email });
  assert.equal(res.status, 202);
  const tokens = extractTokens(server.logs.slice(before));
  assert.equal(tokens.length, 1, 'exactly one reset token should be logged in debug mode');
  return tokens[0];
}

test('forgot-password always returns 202 {ok:true}, existing or not', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '1' });
  t.after(() => server.close());

  await registerUser(server, { username: 'resetter', email: 'resetter@example.com' });

  const existing = await postJson(server.baseUrl, '/auth/forgot-password', { email: 'resetter@example.com' });
  const missing = await postJson(server.baseUrl, '/auth/forgot-password', { email: 'nobody@example.com' });
  const malformed = await postJson(server.baseUrl, '/auth/forgot-password', { email: 'also-not-a-user@mail.test' });

  assert.equal(existing.status, 202);
  assert.equal(missing.status, 202);
  assert.equal(malformed.status, 202);
  assert.deepEqual(existing.json, { ok: true });
  assert.deepEqual(missing.json, { ok: true });
  // Identical responses: no way to tell whether the address exists.
  assert.deepEqual(existing.json, missing.json);
  assert.equal(existing.headers.get('content-type'), missing.headers.get('content-type'));

  // Bad input still gets a 400 (shape validation is not an oracle for existence).
  const bad = await postJson(server.baseUrl, '/auth/forgot-password', {});
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.code, 'invalid_input');
});

test('reset flow: valid token works once, revokes all sessions, invalid/expired/weak are rejected', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '1' });
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'grace', email: 'grace@example.com' });
  const refreshToken = registered.tokens.refreshToken;
  const accessToken = registered.tokens.accessToken;

  // --- weak new password: rejected, and the token survives (good UX) --------
  const weakToken = await captureResetToken(server, 'grace@example.com');
  const weak = await postJson(server.baseUrl, '/auth/reset-password', {
    token: weakToken,
    newPassword: 'short',
  });
  assert.equal(weak.status, 400);
  assert.equal(weak.json.error.code, 'weak_password');

  // --- valid token ----------------------------------------------------------
  const ok = await postJson(server.baseUrl, '/auth/reset-password', {
    token: weakToken,
    newPassword: 'BrandNewPass1',
  });
  assert.equal(ok.status, 200);
  assert.deepEqual(ok.json, { ok: true });

  // The token was single-use.
  const replay = await postJson(server.baseUrl, '/auth/reset-password', {
    token: weakToken,
    newPassword: 'AnotherPass12',
  });
  assert.equal(replay.status, 400);
  assert.equal(replay.json.error.code, 'invalid_token');

  // Old password dead, new password alive.
  const oldLogin = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'grace',
    password: 'Sup3rSecret!',
  });
  assert.equal(oldLogin.status, 401);
  const newLogin = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'grace',
    password: 'BrandNewPass1',
  });
  assert.equal(newLogin.status, 200);

  // All pre-reset sessions were revoked.
  const refreshAfter = await postJson(server.baseUrl, '/auth/refresh', { refreshToken });
  assert.equal(refreshAfter.status, 401);
  assert.equal(refreshAfter.json.error.code, 'invalid_session');

  // The access token itself remains a valid JWT until it expires (documented).
  const me = await request(server.baseUrl, '/auth/me', { token: accessToken });
  assert.equal(me.status, 200);

  // --- invalid token --------------------------------------------------------
  const invalid = await postJson(server.baseUrl, '/auth/reset-password', {
    token: 'this-is-not-a-real-reset-token',
    newPassword: 'BrandNewPass1',
  });
  assert.equal(invalid.status, 400);
  assert.equal(invalid.json.error.code, 'invalid_token');
});

test('expired reset token -> 400 invalid_token', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '1' });
  t.after(() => server.close());

  await registerUser(server, { username: 'expirer', email: 'expirer@example.com' });
  const token = await captureResetToken(server, 'expirer@example.com');

  // Force expiry by rewriting expiresAt on disk.
  const storePath = path.join(server.dataDir, 'reset-tokens.json');
  const data = JSON.parse(await fs.readFile(storePath, 'utf8'));
  for (const record of Object.values(data.tokens)) record.expiresAt = 1;
  await fs.writeFile(storePath, JSON.stringify(data), 'utf8');

  const res = await postJson(server.baseUrl, '/auth/reset-password', {
    token,
    newPassword: 'BrandNewPass1',
  });
  assert.equal(res.status, 400);
  assert.equal(res.json.error.code, 'invalid_token');
});

test('reset tokens are stored as SHA-256 only', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '1' });
  t.after(() => server.close());

  await registerUser(server, { username: 'hasher', email: 'hasher@example.com' });
  const token = await captureResetToken(server, 'hasher@example.com');

  const raw = await fs.readFile(path.join(server.dataDir, 'reset-tokens.json'), 'utf8');
  assert.ok(!raw.includes(token), 'raw reset token must never be written to disk');
  const data = JSON.parse(raw);
  for (const key of Object.keys(data.tokens)) {
    assert.match(key, /^[0-9a-f]{64}$/, 'storage keys must be sha256 hex');
  }
});

test('DEBUG_RESET_TOKENS=0 never logs the token', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '0' });
  t.after(() => server.close());

  await registerUser(server, { username: 'quiet', email: 'quiet@example.com' });
  const before = server.logs.length;
  const res = await postJson(server.baseUrl, '/auth/forgot-password', { email: 'quiet@example.com' });
  assert.equal(res.status, 202);

  const newLogs = server.logs.slice(before);
  assert.equal(extractTokens(newLogs).length, 0, 'no raw token may appear in logs');
  assert.ok(
    newLogs.some((line) => line.includes('token=<redacted>')),
    'the log line should explicitly show the token was redacted',
  );
});

test('the token is NEVER logged in production even when DEBUG_RESET_TOKENS=1', async (t) => {
  const server = await startServer({
    NODE_ENV: 'production',
    JWT_SECRET: 'production-grade-secret-with-at-least-32-characters!!',
    DEBUG_RESET_TOKENS: '1',
  });
  t.after(() => server.close());

  await registerUser(server, { username: 'produser', email: 'prod@example.com' });
  const before = server.logs.length;
  const res = await postJson(server.baseUrl, '/auth/forgot-password', { email: 'prod@example.com' });
  assert.equal(res.status, 202);

  const newLogs = server.logs.slice(before);
  assert.equal(extractTokens(newLogs).length, 0, 'production logs must never contain a reset token');
  assert.ok(
    newLogs.some((line) => line.includes('token=<redacted>')),
    'expected a redacted log line',
  );
});
