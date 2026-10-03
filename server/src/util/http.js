/**
 * Small HTTP helpers: JSON responses and the uniform error envelope.
 *
 * Every error response in this server has exactly this shape:
 *   { "error": { "code": "...", "message": "..." } }
 */

export class HttpError extends Error {
  /**
   * @param {number} status HTTP status code
   * @param {code} code stable machine-readable error code
   * @param {string} message human-readable message (safe to show to clients)
   */
  constructor(status, code, message) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
    this.expose = true;
  }
}

/** Build the uniform error envelope body. */
export function errorBody(code, message) {
  return { error: { code, message } };
}

/** Send a JSON payload with the given status. */
export function sendJson(res, status, body) {
  return res.status(status).json(body);
}

/** Throw helper (keeps call sites terse). */
export function httpError(status, code, message) {
  throw new HttpError(status, code, message);
}

/** Best-effort client IP (works with/without trust proxy). */
export function clientIp(req) {
  return req.ip || req.socket?.remoteAddress || 'unknown';
}

/** Wrap an async route handler so rejections reach the error handler (Express 4). */
export function asyncHandler(fn) {
  return function wrapped(req, res, next) {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
}
