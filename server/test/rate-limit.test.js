/**
 * Rate limiting: in-memory sliding window per IP + route class.
 * The login limiter allows 10 requests / 15 minutes per IP.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { startServer, postJson } from './helpers.js';

const LOGIN_ATTEMPT = { usernameOrEmail: 'nobody', password: 'wrong-password' };

test('login limiter returns 429 + Retry-After after 10 attempts in the window', async (t) => {
  const server = await startServer({ RATE_LIMIT_DISABLED: '0' });
  t.after(() => server.close());

  const statuses = [];
  let limited = null;
  for (let i = 0; i < 11; i++) {
    const res = await postJson(server.baseUrl, '/auth/login', LOGIN_ATTEMPT);
    statuses.push(res.status);
    if (res.status === 429) {
      limited = res;
      break;
    }
  }

  // The first 10 attempts get the normal 401, the 11th is rate limited.
  assert.deepEqual(statuses.slice(0, 10), Array(10).fill(401));
  assert.equal(statuses[10], 429, 'the 11th request within the window must be rate limited');
  assert.equal(limited.json.error.code, 'rate_limited');

  const retryAfter = limited.headers.get('retry-after');
  assert.ok(retryAfter, '429 responses must carry Retry-After');
  const seconds = Number(retryAfter);
  assert.ok(Number.isInteger(seconds) && seconds >= 1 && seconds <= 15 * 60, `unexpected Retry-After: ${retryAfter}`);

  // The limiter is per route class: health checks are unaffected.
  const health = await fetch(`${server.baseUrl}/health`);
  assert.equal(health.status, 200);
});

test('RATE_LIMIT_DISABLED=1 skips all limiters', async (t) => {
  const server = await startServer({ RATE_LIMIT_DISABLED: '1' });
  t.after(() => server.close());

  const statuses = [];
  for (let i = 0; i < 15; i++) {
    const res = await postJson(server.baseUrl, '/auth/login', LOGIN_ATTEMPT);
    statuses.push(res.status);
  }
  assert.ok(
    statuses.every((s) => s === 401),
    `no request may be rate limited when disabled, got: ${statuses.join(',')}`,
  );
});
