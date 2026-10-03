/**
 * Express app factory (exportable for tests).
 *
 * Order: security headers -> access log -> JSON body (64kb) -> /health ->
 * /auth/* -> /models/* -> JSON 404 -> uniform error handler.
 */
import express from 'express';
import { loadConfig } from './config/index.js';
import { securityHeaders, jsonBodyLimit } from './middleware/security.js';
import { createRequestLog } from './middleware/requestLog.js';
import { buildRateLimiters } from './middleware/rateLimit.js';
import { createErrorHandler, notFoundHandler } from './middleware/errorHandler.js';
import { createRequireAuth } from './middleware/requireAuth.js';
import { createJsonStore } from './util/jsonStore.js';
import { createUserStore } from './users/store.js';
import { createTokenManager } from './auth/tokens.js';
import { createEmailSender } from './auth/emails.js';
import { createGoogleVerifier } from './auth/google.js';
import { createAuthService } from './auth/service.js';
import { registerAuthRoutes } from './auth/routes.js';
import { createModelsStore } from './models/store.js';
import { registerModelRoutes } from './models/routes.js';

/**
 * @param {{config?: object, logger?: object,
 *          googleVerifier?: ((idToken: string) => Promise<object>)|null}} [options]
 * @returns {import('express').Express}
 */
export function createApp(options = {}) {
  const config = options.config ?? loadConfig();
  const logger = options.logger ?? console;

  const app = express();
  app.disable('x-powered-by');
  if (config.TRUST_PROXY) app.set('trust proxy', config.TRUST_PROXY);

  app.use(securityHeaders);
  app.use(createRequestLog({ logger }));
  app.use(jsonBodyLimit());

  // ---- services -----------------------------------------------------------
  const users = createUserStore({ config });
  const refreshStore = createJsonStore(`${config.DATA_DIR}/refresh-tokens.json`, {
    defaultValue: { tokens: {} },
  });
  const resetStore = createJsonStore(`${config.DATA_DIR}/reset-tokens.json`, {
    defaultValue: { tokens: {} },
  });
  const tokens = createTokenManager({ config, refreshStore, resetStore });
  const emailSender = createEmailSender({ config, logger });
  const verifyGoogleIdToken =
    options.googleVerifier !== undefined
      ? options.googleVerifier
      : createGoogleVerifier({ googleClientId: config.GOOGLE_CLIENT_ID });
  const auth = createAuthService({ config, users, tokens, emailSender, verifyGoogleIdToken, logger });
  const requireAuth = createRequireAuth({ tokens, users });
  const rateLimiters = buildRateLimiters(config);
  const models = createModelsStore({ config });

  // ---- routes (the complete allowlist — see test/route-allowlist.test.js) --
  app.get('/health', (req, res) => {
    res.json({ status: 'ok' });
  });
  registerAuthRoutes(app, { auth, requireAuth, rateLimiters });
  registerModelRoutes(app, { models, config, requireAuth, rateLimiters });

  // ---- terminal middleware ------------------------------------------------
  app.use(notFoundHandler);
  app.use(createErrorHandler({ config, logger }));

  app.locals.config = config;
  app.locals.services = { users, tokens, refreshStore, resetStore, emailSender, auth, models };
  return app;
}
