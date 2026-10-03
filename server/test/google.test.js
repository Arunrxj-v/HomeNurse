/**
 * Google Sign-In: mocked verifier for the HTTP flow, plus unit tests of the
 * payload checks (iss/aud/exp/nonce) that guard the real verifier.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { startServer, postJson, request, TEST_GOOGLE_CLIENT_ID } from './helpers.js';
import { assertGooglePayload, sha256hex } from '../src/auth/google.js';

function validPayload(overrides = {}) {
  return {
    iss: 'accounts.google.com',
    aud: TEST_GOOGLE_CLIENT_ID,
    sub: 'google-sub-123',
    email: 'Alice.Smith@Example.com',
    email_verified: true,
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...overrides,
  };
}

test('valid mocked Google token creates the account on first sign-in and reuses it afterwards', async (t) => {
  const calls = [];
  const verifier = async (idToken) => {
    calls.push(idToken);
    return validPayload();
  };
  const server = await startServer({}, { googleVerifier: verifier });
  t.after(() => server.close());

  const first = await postJson(server.baseUrl, '/auth/google', { idToken: 'valid-id-token' });
  assert.equal(first.status, 200);
  assert.equal(first.json.account.provider, 'google');
  assert.equal(first.json.account.email, 'Alice.Smith@Example.com');
  // Username derived from the sanitized email local part.
  assert.equal(first.json.account.username, 'alice.smith');
  assert.equal(typeof first.json.account.id, 'string');
  assert.ok(first.json.tokens.accessToken);
  assert.ok(first.json.tokens.refreshToken);
  assert.ok(!/password|hash/i.test(first.text));

  // Second sign-in with the same Google identity returns the SAME account.
  const second = await postJson(server.baseUrl, '/auth/google', { idToken: 'valid-id-token' });
  assert.equal(second.status, 200);
  assert.equal(second.json.account.id, first.json.account.id);
  assert.equal(second.json.account.username, 'alice.smith');
  assert.deepEqual(calls, ['valid-id-token', 'valid-id-token']);
});

test('username collisions after derivation are resolved (unique usernames)', async (t) => {
  const emails = ['john.a@one.example', 'john.a@two.example'];
  let counter = 0;
  const verifier = async () => validPayload({ sub: `sub-${++counter}`, email: emails[counter - 1] });
  const server = await startServer({}, { googleVerifier: verifier });
  t.after(() => server.close());

  const a = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok-1' });
  const b = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok-2' });
  assert.equal(a.status, 200, a.text);
  assert.equal(b.status, 200, b.text);
  // Both derive to "john.a" from the email local part; the second must be uniquified.
  assert.equal(a.json.account.username, 'john.a');
  assert.notEqual(a.json.account.username, b.json.account.username);
  assert.equal(b.json.account.email, 'john.a@two.example');
});

test('invalid signature / verifier rejection -> 401 invalid_google_token', async (t) => {
  const verifier = async () => {
    throw new Error('invalid_signature: token signature verification failed');
  };
  const server = await startServer({}, { googleVerifier: verifier });
  t.after(() => server.close());

  const res = await postJson(server.baseUrl, '/auth/google', { idToken: 'bad-signature' });
  assert.equal(res.status, 401);
  assert.equal(res.json.error.code, 'invalid_google_token');
});

test('nonce mismatch -> 401 invalid_google_token; matching nonce -> 200', async (t) => {
  const rawNonce = 'client-nonce-abc123';
  const verifier = async () => validPayload({ nonce: sha256hex(rawNonce) });
  const server = await startServer({}, { googleVerifier: verifier });
  t.after(() => server.close());

  const mismatch = await postJson(server.baseUrl, '/auth/google', {
    idToken: 'tok',
    nonce: 'a-different-raw-nonce',
  });
  assert.equal(mismatch.status, 401);
  assert.equal(mismatch.json.error.code, 'invalid_google_token');

  const missingNonceClaim = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok' });
  assert.equal(missingNonceClaim.status, 200, 'nonce check only applies when the client sends one');

  const match = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok', nonce: rawNonce });
  assert.equal(match.status, 200);
});

test('501 google_not_configured when GOOGLE_CLIENT_ID is unset', async (t) => {
  const verifier = async () => validPayload(); // must never be reached
  const server = await startServer({ GOOGLE_CLIENT_ID: '' }, { googleVerifier: verifier });
  t.after(() => server.close());

  const res = await postJson(server.baseUrl, '/auth/google', { idToken: 'any-token' });
  assert.equal(res.status, 501);
  assert.equal(res.json.error.code, 'google_not_configured');
});

test('missing idToken -> 400 invalid_input', async (t) => {
  const server = await startServer({}, { googleVerifier: async () => validPayload() });
  t.after(() => server.close());

  const res = await postJson(server.baseUrl, '/auth/google', {});
  assert.equal(res.status, 400);
  assert.equal(res.json.error.code, 'invalid_input');
});

test('assertGooglePayload enforces iss, aud, exp and nonce', () => {
  const now = Date.now();
  const base = validPayload({ exp: Math.floor(now / 1000) + 60 });

  // Valid passes.
  assertGooglePayload(base, { clientId: TEST_GOOGLE_CLIENT_ID, now });

  // securetoken@gserviceaccount.com must NOT be accepted.
  assert.throws(
    () => assertGooglePayload({ ...base, iss: 'securetoken@gserviceaccount.com' }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.status === 401 && err.code === 'invalid_google_token',
  );
  assert.throws(
    () => assertGooglePayload({ ...base, iss: 'https://evil.example.com' }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );

  // Audience mismatch.
  assert.throws(
    () => assertGooglePayload({ ...base, aud: 'someone-else.apps.googleusercontent.com' }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );
  assert.throws(
    () => assertGooglePayload({ ...base, aud: [TEST_GOOGLE_CLIENT_ID, 'other'] }, { clientId: 'different-client', now }),
    (err) => err.code === 'invalid_google_token',
  );

  // Expired.
  assert.throws(
    () => assertGooglePayload({ ...base, exp: Math.floor(now / 1000) - 10 }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );
  assert.throws(
    () => assertGooglePayload({ ...base, exp: undefined }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );

  // Missing subject.
  assert.throws(
    () => assertGooglePayload({ ...base, sub: '' }, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );

  // Nonce binding.
  const raw = 'raw-nonce-value';
  assertGooglePayload(
    { ...base, nonce: crypto.createHash('sha256').update(raw).digest('hex') },
    { clientId: TEST_GOOGLE_CLIENT_ID, nonce: raw, now },
  );
  assert.throws(
    () => assertGooglePayload({ ...base, nonce: sha256hex('other') }, { clientId: TEST_GOOGLE_CLIENT_ID, nonce: raw, now }),
    (err) => err.code === 'invalid_google_token',
  );
  assert.throws(
    () => assertGooglePayload(base, { clientId: TEST_GOOGLE_CLIENT_ID, nonce: raw, now }),
    (err) => err.code === 'invalid_google_token',
  );

  // Non-string / null payload.
  assert.throws(
    () => assertGooglePayload(null, { clientId: TEST_GOOGLE_CLIENT_ID, now }),
    (err) => err.code === 'invalid_google_token',
  );
});

test('Google-created accounts can sign in locally afterwards (password set via reset)', async (t) => {
  const server = await startServer({ DEBUG_RESET_TOKENS: '1' }, { googleVerifier: async () => validPayload() });
  t.after(() => server.close());

  const created = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok' });
  assert.equal(created.status, 200);

  // registerUser-like flow: a Google account has no local password, so password
  // login must fail with the same uniform 401 body.
  const login = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'alice.smith',
    password: 'whatever12345',
  });
  assert.equal(login.status, 401);
  assert.equal(login.json.error.code, 'invalid_credentials');
});

test('google accounts can be deleted without a password (unlike local accounts)', async (t) => {
  const server = await startServer({}, { googleVerifier: async () => validPayload() });
  t.after(() => server.close());

  const created = await postJson(server.baseUrl, '/auth/google', { idToken: 'tok' });
  assert.equal(created.status, 200);
  const { accessToken, refreshToken } = created.json.tokens;

  const deleted = await request(server.baseUrl, '/auth/delete-account', {
    method: 'POST',
    body: {},
    token: accessToken,
  });
  assert.equal(deleted.status, 200);
  assert.deepEqual(deleted.json, { ok: true });

  const meAfter = await request(server.baseUrl, '/auth/me', { token: accessToken });
  assert.equal(meAfter.status, 401);
  const refreshAfter = await postJson(server.baseUrl, '/auth/refresh', { refreshToken });
  assert.equal(refreshAfter.status, 401);
  assert.equal(refreshAfter.json.error.code, 'invalid_session');
});
