/**
 * Access log: method, path, status, duration ms, ip.
 *
 * PRIVACY: request bodies are never read here and the Authorization header (or
 * any token value) is never logged. Query strings are also excluded — only
 * req.path is logged.
 */
import { clientIp } from '../util/http.js';

export function createRequestLog({ logger = console, now = () => Date.now() } = {}) {
  return function requestLog(req, res, next) {
    const start = now();
    const ip = clientIp(req);
    res.on('finish', () => {
      const durationMs = now() - start;
      const line = `${req.method} ${req.path} ${res.statusCode} ${durationMs}ms ip=${ip}`;
      if (typeof logger.info === 'function') logger.info(line);
      else logger.log?.(line);
    });
    next();
  };
}
