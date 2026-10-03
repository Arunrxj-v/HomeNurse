/**
 * Uniform error handler.
 *
 * Every error response is: HTTP status + { "error": { "code", "message" } }.
 * Stack traces are logged server-side but NEVER sent to clients (and, in
 * production, internal error messages are replaced by a generic one).
 */
import { errorBody, sendJson } from '../util/http.js';

export function createErrorHandler({ config, logger = console } = {}) {
  const isProduction = config?.isProduction ?? process.env.NODE_ENV === 'production';

  // eslint-disable-next-line no-unused-vars -- Express identifies error handlers by arity (4 params).
  return function errorHandler(err, req, res, _next) {
    let status = err?.status || err?.statusCode || 500;
    let code = typeof err?.code === 'string' ? err.code : 'internal_error';
    let message = err?.message || 'Internal server error';

    // Body-parser failures.
    if (err?.type === 'entity.parse.failed') {
      status = 400;
      code = 'invalid_json';
      message = 'Request body is not valid JSON.';
    } else if (err?.type === 'entity.too.large') {
      status = 413;
      code = 'payload_too_large';
      message = 'Request body is too large.';
    } else if (err?.type === 'charset.unsupported' || err?.type === 'encoding.unsupported') {
      status = 415;
      code = 'unsupported_media_type';
      message = 'Unsupported request encoding.';
    } else if (err instanceof URIError) {
      status = 400;
      code = 'invalid_input';
      message = 'Malformed request.';
    }

    if (status >= 500) {
      // Log full details server-side only.
      const detail = err?.stack || err?.message || String(err);
      logger.error?.(`request_error ${req.method} ${req.path} ${status}: ${detail}`);
      // Production masks internals — except the intentional, non-sensitive
      // 501 google_not_configured, which clients need to tell "Google
      // sign-in not set up" apart from a real server failure.
      if (isProduction && code !== 'google_not_configured') {
        code = 'internal_error';
        message = 'Internal server error';
      }
    }

    if (res.headersSent) return;
    return sendJson(res, status, errorBody(code, message));
  };
}

/** Terminal 404 handler (JSON, not HTML). Registered after all routes. */
export function notFoundHandler(req, res) {
  return sendJson(res, 404, errorBody('not_found', 'Not found'));
}
