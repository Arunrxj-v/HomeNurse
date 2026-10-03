/**
 * Core account lifecycle: register -> login -> /auth/me, input validation,
 * duplicate detection, credential-error uniformity, account deletion, and
 * proof that no password material ever leaves the server.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { startServer, postJson, request, registerUser } from './helpers.js';
import { verifyPassword, getPasswordBackend } from '../src/auth/passwords.js';

test('register returns 201 with account + tokens and never leaks password material', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const res = await postJson(server.baseUrl, '/auth/register', {
    username: 'alice',
    email: 'alice@example.com',
    password: 'Sup3rSecret!',
  });

  assert.equal(res.status, 201);
  assert.deepEqual(Object.keys(res.json).sort(), ['account', 'tokens']);
  assert.equal(typeof res.json.account.id, 'string');
  assert.deepEqual(res.json.account, {
    id: res.json.account.id,
    username: 'alice',
    email: 'alice@example.com',
    provider: 'local',
  });

  const { tokens } = res.json;
  assert.equal(typeof tokens.accessToken, 'string');
  assert.equal(typeof tokens.refreshToken, 'string');
  assert.equal(tokens.accessTokenExpiresIn, server.config.ACCESS_TOKEN_TTL);
  assert.equal(tokens.refreshTokenExpiresIn, server.config.REFRESH_TOKEN_TTL);

  // Response text must contain no password/hash material at all.
  assert.ok(!/hash/i.test(res.text), 'response must not contain "hash"');
  assert.ok(!/password/i.test(res.text), 'response must not contain "password"');
  assert.ok(!res.text.includes('Sup3rSecret!'), 'response must not echo the password');
});

test('stored user record has no plaintext password; argon2id verify accepts only the right password', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const password = 'CorrectHorse42';
  const registered = await registerUser(server, { username: 'bob', password });

  const raw = await fs.readFile(path.join(server.dataDir, 'users.json'), 'utf8');
  const data = JSON.parse(raw);
  const user = Object.values(data.users)[0];

  assert.equal(user.username, 'bob');
  assert.ok(user.passwordHash, 'a password hash must be stored');
  assert.ok(!raw.includes(password), 'plaintext password must never be persisted');
  assert.ok(user.passwordHash.startsWith('$argon2id$'), `expected argon2id hash, got ${user.passwordHash}`);
  assert.match(user.passwordHash, /^\$argon2id\$v=19\$m=19456,t=2,p=1\$/, 'OWASP argon2id parameters');

  assert.equal(await verifyPassword(user.passwordHash, password), true);
  assert.equal(await verifyPassword(user.passwordHash, 'WrongPassword42'), false);
  assert.equal(await verifyPassword(user.passwordHash, ''), false);

  // Sanity: the active backend really is argon2 (native module installed).
  const backend = await getPasswordBackend();
  assert.equal(backend.name, 'argon2');
  void registered;
});

test('login succeeds and /auth/me returns the account without hash/password keys', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'carol', password: 'Passw0rd!aa' });

  const login = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'carol',
    password: 'Passw0rd!aa',
  });
  assert.equal(login.status, 200);
  assert.equal(login.json.account.username, 'carol');
  assert.ok(login.json.tokens.accessToken);

  // Login also works with the email address.
  const loginByEmail = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'carol@example.com',
    password: 'Passw0rd!aa',
  });
  assert.equal(loginByEmail.status, 200);

  const me = await request(server.baseUrl, '/auth/me', { token: login.json.tokens.accessToken });
  assert.equal(me.status, 200);
  assert.deepEqual(me.json.account, {
    id: registered.account.id,
    username: 'carol',
    email: 'carol@example.com',
    provider: 'local',
  });

  // "never leaks password hash" — assert the raw response text.
  assert.ok(!/hash/i.test(me.text), `response must not contain "hash": ${me.text}`);
  assert.ok(!/password/i.test(me.text), `response must not contain "password": ${me.text}`);
});

test('/auth/me without a token (or with a garbage token) is 401 unauthorized', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const noToken = await request(server.baseUrl, '/auth/me');
  assert.equal(noToken.status, 401);
  assert.equal(noToken.json.error.code, 'unauthorized');

  const garbage = await request(server.baseUrl, '/auth/me', { token: 'not-a-jwt' });
  assert.equal(garbage.status, 401);
  assert.equal(garbage.json.error.code, 'unauthorized');

  const wrongScheme = await request(server.baseUrl, '/auth/me', {
    headers: { authorization: 'Basic dXNlcjpwYXNz' },
  });
  assert.equal(wrongScheme.status, 401);
  assert.equal(wrongScheme.json.error.code, 'unauthorized');
});

test('invalid credentials: unknown user and wrong password produce the IDENTICAL 401 body', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  await registerUser(server, { username: 'dave', password: 'RealPassword1' });

  const unknownUser = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'no-such-user',
    password: 'whatever12345',
  });
  const wrongPassword = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'dave',
    password: 'wrong-password',
  });

  assert.equal(unknownUser.status, 401);
  assert.equal(wrongPassword.status, 401);
  assert.equal(unknownUser.json.error.code, 'invalid_credentials');
  assert.equal(wrongPassword.json.error.code, 'invalid_credentials');
  assert.deepEqual(unknownUser.json, wrongPassword.json, 'no user enumeration via response body');
  assert.deepEqual(unknownUser.headers.get('content-type'), wrongPassword.headers.get('content-type'));
});

test('validation failures return 400 invalid_input with the right messages', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const cases = [
    { body: { username: 'ab', email: 'a@b.co', password: 'longenough' }, match: /username/ },
    { body: { username: 'has spaces', email: 'a@b.co', password: 'longenough' }, match: /username/ },
    { body: { username: 'ok_name', email: 'not-an-email', password: 'longenough' }, match: /email/ },
    { body: { username: 'ok_name', email: 'a@b.co' }, match: /password/ },
    { body: {}, match: /username/ },
  ];
  for (const { body, match } of cases) {
    const res = await postJson(server.baseUrl, '/auth/register', body);
    assert.equal(res.status, 400, `expected 400 for ${JSON.stringify(body)}`);
    assert.equal(res.json.error.code, 'invalid_input');
    assert.match(res.json.error.message, match);
  }

  const badLogin = await postJson(server.baseUrl, '/auth/login', { usernameOrEmail: '', password: 'x' });
  assert.equal(badLogin.status, 400);
  assert.equal(badLogin.json.error.code, 'invalid_input');
});

test('weak passwords are rejected with 400 weak_password', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const cases = [
    { username: 'user1', email: 'user1@example.com', password: 'short7' }, // < 8 chars? no -> 6 chars ok test
    { username: 'user1', email: 'user1@example.com', password: 'abc' },
    { username: 'user1', email: 'user1@example.com', password: '        ' }, // only whitespace
    { username: 'user1', email: 'user1@example.com', password: 'user1' }, // equals username (too short anyway)
    { username: 'user1', email: 'user1@example.com', password: 'user1user1' }, // hmm
    { username: 'longname', email: 'longname@example.com', password: 'longname' }, // equals username
    { username: 'longname', email: 'longname@example.com', password: 'longname@example.com' }, // equals email
  ];
  const weakCases = cases.filter((c) => c.password.length < 8 || c.password === c.username || c.password === c.email);
  for (const body of weakCases) {
    const res = await postJson(server.baseUrl, '/auth/register', body);
    assert.equal(res.status, 400, `expected weak password rejection for ${JSON.stringify(body.password)}`);
    assert.equal(res.json.error.code, 'weak_password');
  }
});

test('duplicate username / email are rejected with 409', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  await registerUser(server, { username: 'taken', email: 'taken@example.com' });

  const dupUsername = await postJson(server.baseUrl, '/auth/register', {
    username: 'taken',
    email: 'other@example.com',
    password: 'AnotherPass1',
  });
  assert.equal(dupUsername.status, 409);
  assert.equal(dupUsername.json.error.code, 'username_taken');

  // Uniqueness is case-insensitive.
  const dupUsernameCase = await postJson(server.baseUrl, '/auth/register', {
    username: 'TAKEN',
    email: 'third@example.com',
    password: 'AnotherPass1',
  });
  assert.equal(dupUsernameCase.status, 409);
  assert.equal(dupUsernameCase.json.error.code, 'username_taken');

  const dupEmail = await postJson(server.baseUrl, '/auth/register', {
    username: 'freshname',
    email: 'taken@example.com',
    password: 'AnotherPass1',
  });
  assert.equal(dupEmail.status, 409);
  assert.equal(dupEmail.json.error.code, 'email_taken');
});

test('delete-account: password required for local accounts, verified, and fully effective', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const registered = await registerUser(server, { username: 'frank', password: 'DeleteMe123' });
  const accessToken = registered.tokens.accessToken;
  const refreshToken = registered.tokens.refreshToken;

  const missingPassword = await request(server.baseUrl, '/auth/delete-account', {
    method: 'POST',
    body: {},
    token: accessToken,
  });
  assert.equal(missingPassword.status, 400);
  assert.equal(missingPassword.json.error.code, 'password_required');

  const wrongPassword = await request(server.baseUrl, '/auth/delete-account', {
    method: 'POST',
    body: { password: 'not-the-password' },
    token: accessToken,
  });
  assert.equal(wrongPassword.status, 401);
  assert.equal(wrongPassword.json.error.code, 'invalid_credentials');

  const unauthenticated = await request(server.baseUrl, '/auth/delete-account', {
    method: 'POST',
    body: { password: 'DeleteMe123' },
  });
  assert.equal(unauthenticated.status, 401);

  const deleted = await request(server.baseUrl, '/auth/delete-account', {
    method: 'POST',
    body: { password: 'DeleteMe123' },
    token: accessToken,
  });
  assert.equal(deleted.status, 200);
  assert.deepEqual(deleted.json, { ok: true });

  // The account and all of its sessions are gone.
  const meAfter = await request(server.baseUrl, '/auth/me', { token: accessToken });
  assert.equal(meAfter.status, 401);

  const refreshAfter = await postJson(server.baseUrl, '/auth/refresh', { refreshToken });
  assert.equal(refreshAfter.status, 401);
  assert.equal(refreshAfter.json.error.code, 'invalid_session');

  const loginAfter = await postJson(server.baseUrl, '/auth/login', {
    usernameOrEmail: 'frank',
    password: 'DeleteMe123',
  });
  assert.equal(loginAfter.status, 401);
  assert.equal(loginAfter.json.error.code, 'invalid_credentials');
});

test('malformed JSON and oversized bodies are rejected with the error envelope', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const malformed = await request(server.baseUrl, '/auth/login', {
    method: 'POST',
    rawBody: '{"usernameOrEmail":',
    headers: { 'content-type': 'application/json' },
  });
  assert.equal(malformed.status, 400);
  assert.equal(malformed.json.error.code, 'invalid_json');

  const huge = await request(server.baseUrl, '/auth/register', {
    method: 'POST',
    rawBody: JSON.stringify({ username: 'bigbody', email: 'big@example.com', password: 'x'.repeat(100 * 1024) }),
    headers: { 'content-type': 'application/json' },
  });
  assert.equal(huge.status, 413);
  assert.equal(huge.json.error.code, 'payload_too_large');
});
