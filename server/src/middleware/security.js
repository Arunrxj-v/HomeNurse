/**
 * Security headers + the JSON body parser (64kb limit).
 *
 * HSTS is only emitted for HTTPS requests, which requires `app.set('trust proxy')`
 * to be configured (TRUST_PROXY env) when running behind a TLS-terminating proxy.
 */
import express from 'express';

export function securityHeaders(req, res, next) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  if (req.secure) {
    res.setHeader('Strict-Transport-Security', 'max-age=15552000; includeSubDomains');
  }
  next();
}

/** JSON body parser with a hard 64kb request-body limit. */
export function jsonBodyLimit() {
  return express.json({ limit: '64kb' });
}
