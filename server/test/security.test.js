/**
 * Security middleware: headers, HSTS gating, JSON body limit, and the
 * access-log privacy guarantee (never Authorization / bodies / tokens).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { startServer, request, registerUser } from './helpers.js';

test('request log records method, path, status, duration and IP — never Authorization or bodies', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const password = 'LogPassw0rd!NotInLogs';
  const account = await registerUser(server, { username: 'loguser', password });
  const token = account.tokens.accessToken;

  const me = await request(server.baseUrl, '/auth/me', { token });
  assert.equal(me.status, 200);

  const all = server.logs.join('\n');

  // The log lines have the documented shape.
  assert.ok(
    server.logs.some((line) => /^POST \/auth\/register 201 \d+ms ip=\S+/.test(line)),
    `expected a register log line, got:\n${all}`,
  );
  assert.ok(
    server.logs.some((line) => /^GET \/auth\/me 200 \d+ms ip=\S+/.test(line)),
    `expected an /auth/me log line, got:\n${all}`,
  );

  // No secrets, tokens, or credentials of any kind.
  assert.ok(!all.includes(token), 'logs must never contain the access token');
  assert.ok(!all.includes(account.tokens.refreshToken), 'logs must never contain the refresh token');
  assert.ok(!all.includes(password), 'logs must never contain a password');
  assert.ok(!/authorization/i.test(all), 'logs must never reference the Authorization header');
  assert.ok(!/bearer/i.test(all), 'logs must never contain "Bearer"');
  assert.ok(!/\beyJ[A-Za-z0-9_-]+\./.test(all), 'logs must never contain a JWT');

  // Query strings (and therefore any token in one) are excluded.
  const withQuery = await request(server.baseUrl, '/auth/me?access_token=nope', { token });
  assert.equal(withQuery.status, 200);
  assert.ok(!server.logs.join('\n').includes('access_token'), 'query strings must not be logged');
});

test('security headers are set and x-powered-by is disabled', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const res = await request(server.baseUrl, '/health');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
  assert.equal(res.headers.get('x-frame-options'), 'DENY');
  assert.equal(res.headers.get('x-powered-by'), null);

  // Also on error responses.
  const notFound = await request(server.baseUrl, '/nope');
  assert.equal(notFound.status, 404);
  assert.equal(notFound.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(notFound.headers.get('x-powered-by'), null);
});

test('HSTS is emitted only for HTTPS requests behind a trusted proxy', async (t) => {
  const server = await startServer({ TRUST_PROXY: '1' });
  t.after(() => server.close());

  const plain = await request(server.baseUrl, '/health');
  assert.equal(plain.headers.get('strict-transport-security'), null);

  const https = await request(server.baseUrl, '/health', {
    headers: { 'x-forwarded-proto': 'https' },
  });
  assert.equal(https.headers.get('strict-transport-security'), 'max-age=15552000; includeSubDomains');
});

test('without trust proxy, a spoofed x-forwarded-proto does not enable HSTS', async (t) => {
  const server = await startServer({ TRUST_PROXY: '' });
  t.after(() => server.close());

  const spoofed = await request(server.baseUrl, '/health', {
    headers: { 'x-forwarded-proto': 'https' },
  });
  assert.equal(spoofed.headers.get('strict-transport-security'), null);
});
