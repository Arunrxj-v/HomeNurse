/**
 * All /auth/* HTTP endpoints.
 *
 * Only account authentication lives here: register, login, Google sign-in,
 * refresh, logout, password reset, profile (me) and account deletion.
 * There are no medical-data endpoints anywhere in this server (enforced by
 * test/route-allowlist.test.js).
 */
import { asyncHandler, sendJson } from '../util/http.js';
import {
  validateRegisterBody,
  validateLoginBody,
  validateGoogleBody,
  validateRefreshBody,
  validateLogoutBody,
  validateForgotPasswordBody,
  validateResetPasswordBody,
  validateDeleteAccountBody,
} from './validation.js';

/**
 * @param {import('express').Express} app
 * @param {{auth: object, requireAuth: Function, rateLimiters: Record<string, Function>}} deps
 */
export function registerAuthRoutes(app, { auth, requireAuth, rateLimiters }) {
  // POST /auth/register -> 201 { account, tokens }
  app.post(
    '/auth/register',
    rateLimiters.register,
    asyncHandler(async (req, res) => {
      const input = validateRegisterBody(req);
      const result = await auth.register(input);
      sendJson(res, 201, result);
    }),
  );

  // POST /auth/login -> 200 { account, tokens }
  app.post(
    '/auth/login',
    rateLimiters.login,
    asyncHandler(async (req, res) => {
      const input = validateLoginBody(req);
      const result = await auth.login(input);
      sendJson(res, 200, result);
    }),
  );

  // POST /auth/google -> 200 { account, tokens }
  app.post(
    '/auth/google',
    rateLimiters.google,
    asyncHandler(async (req, res) => {
      const input = validateGoogleBody(req);
      const result = await auth.googleLogin(input);
      sendJson(res, 200, result);
    }),
  );

  // POST /auth/refresh -> 200 { tokens }
  app.post(
    '/auth/refresh',
    rateLimiters.refresh,
    asyncHandler(async (req, res) => {
      const { refreshToken } = validateRefreshBody(req);
      const result = await auth.refresh(refreshToken);
      sendJson(res, 200, result);
    }),
  );

  // POST /auth/logout -> 204 (always; idempotent)
  app.post(
    '/auth/logout',
    asyncHandler(async (req, res) => {
      const { refreshToken } = validateLogoutBody(req);
      if (refreshToken) await auth.logout(refreshToken);
      res.status(204).end();
    }),
  );

  // POST /auth/forgot-password -> 202 { ok: true } (always)
  app.post(
    '/auth/forgot-password',
    rateLimiters.forgotPassword,
    asyncHandler(async (req, res) => {
      const { email } = validateForgotPasswordBody(req);
      await auth.forgotPassword(email);
      sendJson(res, 202, { ok: true });
    }),
  );

  // POST /auth/reset-password -> 200 { ok: true }
  app.post(
    '/auth/reset-password',
    rateLimiters.resetPassword,
    asyncHandler(async (req, res) => {
      const input = validateResetPasswordBody(req);
      const result = await auth.resetPassword(input);
      sendJson(res, 200, result);
    }),
  );

  // GET /auth/me -> 200 { account }
  app.get(
    '/auth/me',
    requireAuth,
    asyncHandler(async (req, res) => {
      const result = await auth.me(req.user.id);
      sendJson(res, 200, result);
    }),
  );

  // POST /auth/delete-account -> 200 { ok: true }
  app.post(
    '/auth/delete-account',
    requireAuth,
    asyncHandler(async (req, res) => {
      const { password } = validateDeleteAccountBody(req);
      const result = await auth.deleteAccount(req.user.id, password);
      sendJson(res, 200, result);
    }),
  );
}
