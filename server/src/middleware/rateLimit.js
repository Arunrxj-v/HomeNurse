/**
 * In-memory sliding-window rate limiter, keyed by client IP + route class.
 *
 * - Returns 429 with a Retry-After header (seconds) once a route class
 *   exceeds its allowance inside its window.
 * - Entirely disabled when RATE_LIMIT_DISABLED=1 (used by the test suite).
 * - State lives in process memory: it resets on restart and is per-process
 *   (documented limitation for a single-node deployment).
 */
import { HttpError, clientIp } from '../util/http.js';

/** Route-class allowlists (requests / window per IP). */
export const RATE_LIMITS = {
  login: { windowMs: 15 * 60 * 1000, max: 10 },
  register: { windowMs: 60 * 60 * 1000, max: 5 },
  google: { windowMs: 15 * 60 * 1000, max: 10 },
  forgotPassword: { windowMs: 60 * 60 * 1000, max: 5 },
  resetPassword: { windowMs: 60 * 60 * 1000, max: 10 },
  refresh: { windowMs: 15 * 60 * 1000, max: 60 },
  modelsManifest: { windowMs: 60 * 1000, max: 120 },
  modelFile: { windowMs: 60 * 60 * 1000, max: 300 },
};

/**
 * @param {{name: string, windowMs: number, max: number, disabled?: boolean,
 *          now?: () => number}} options
 */
export function createRateLimiter({ name, windowMs, max, disabled = false, now = Date.now }) {
  /** @type {Map<string, number[]>} key -> hit timestamps (ms), ascending */
  const hits = new Map();

  function sweep(at) {
    for (const [key, stamps] of hits) {
      const live = stamps.filter((t) => at - t < windowMs);
      if (live.length === 0) hits.delete(key);
      else hits.set(key, live);
    }
  }

  // Periodic cleanup so idle keys never accumulate. unref() so the timer never
  // keeps the process (or the test runner) alive.
  const sweeper = setInterval(() => sweep(now()), Math.min(windowMs, 60_000));
  sweeper.unref?.();

  return function rateLimit(req, res, next) {
    if (disabled) return next();
    const at = now();
    const key = `${name}:${clientIp(req)}`;
    const stamps = (hits.get(key) ?? []).filter((t) => at - t < windowMs);

    if (stamps.length >= max) {
      hits.set(key, stamps);
      const oldest = stamps[0];
      const retryAfterSec = Math.max(1, Math.ceil((oldest + windowMs - at) / 1000));
      res.setHeader('Retry-After', String(retryAfterSec));
      return next(
        new HttpError(429, 'rate_limited', 'Too many requests, please try again later.'),
      );
    }

    stamps.push(at);
    hits.set(key, stamps);
    if (hits.size > 50_000) sweep(at);
    return next();
  };
}

/** Build one limiter per configured route class. */
export function buildRateLimiters(config) {
  const disabled = Boolean(config?.RATE_LIMIT_DISABLED);
  const limiters = {};
  for (const [name, { windowMs, max }] of Object.entries(RATE_LIMITS)) {
    limiters[name] = createRateLimiter({ name, windowMs, max, disabled });
  }
  return limiters;
}
