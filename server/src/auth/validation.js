/**
 * Server-side input validation for /auth/*.
 *
 * All failures throw HttpError(400, "invalid_input") with a specific message.
 * Password strength is checked separately (weak_password) by the service.
 */
import { HttpError } from '../util/http.js';

const USERNAME_RE = /^[a-zA-Z0-9_.-]+$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const LIMITS = Object.freeze({
  usernameMin: 3,
  usernameMax: 32,
  emailMax: 254,
  passwordMax: 1024,
});

function invalid(message) {
  return new HttpError(400, 'invalid_input', message);
}

function body(req) {
  return req && typeof req.body === 'object' && req.body !== null ? req.body : {};
}

export function validateUsername(value) {
  if (typeof value !== 'string' || value.length === 0) throw invalid('username is required');
  if (value.length < LIMITS.usernameMin || value.length > LIMITS.usernameMax) {
    throw invalid(`username must be between ${LIMITS.usernameMin} and ${LIMITS.usernameMax} characters`);
  }
  if (!USERNAME_RE.test(value)) {
    throw invalid('username may only contain letters, numbers, and the characters _ . -');
  }
  return value;
}

export function validateEmail(value) {
  if (typeof value !== 'string' || value.length === 0) throw invalid('email is required');
  if (value.length > LIMITS.emailMax || !EMAIL_RE.test(value) || value.includes('..')) {
    throw invalid('email must be a valid email address');
  }
  return value;
}

export function validateRegisterBody(req) {
  const b = body(req);
  const username = validateUsername(b.username);
  const email = validateEmail(b.email);
  if (typeof b.password !== 'string' || b.password.length === 0) {
    throw invalid('password is required');
  }
  if (b.password.length > LIMITS.passwordMax) throw invalid('password is too long');
  if (typeof b.email !== 'string') throw invalid('email is required');
  return { username, email: b.email.trim(), password: b.password };
}

export function validateLoginBody(req) {
  const b = body(req);
  if (typeof b.usernameOrEmail !== 'string' || b.usernameOrEmail.trim().length === 0) {
    throw invalid('usernameOrEmail is required');
  }
  if (typeof b.password !== 'string' || b.password.length === 0) {
    throw invalid('password is required');
  }
  if (b.password.length > LIMITS.passwordMax) throw invalid('password is too long');
  return { usernameOrEmail: b.usernameOrEmail.trim(), password: b.password };
}

export function validateGoogleBody(req) {
  const b = body(req);
  if (typeof b.idToken !== 'string' || b.idToken.trim().length === 0) {
    throw invalid('idToken is required');
  }
  if (b.nonce !== undefined && b.nonce !== null) {
    if (typeof b.nonce !== 'string' || b.nonce.length === 0 || b.nonce.length > 512) {
      throw invalid('nonce must be a non-empty string when provided');
    }
  }
  return { idToken: b.idToken.trim(), nonce: typeof b.nonce === 'string' ? b.nonce : undefined };
}

export function validateRefreshBody(req) {
  const b = body(req);
  if (typeof b.refreshToken !== 'string' || b.refreshToken.length === 0) {
    throw invalid('refreshToken is required');
  }
  return { refreshToken: b.refreshToken };
}

export function validateLogoutBody(req) {
  // Logout is idempotent and must never reveal token existence: a missing or
  // malformed body simply revokes nothing and still returns 204.
  const b = body(req);
  return { refreshToken: typeof b.refreshToken === 'string' ? b.refreshToken : undefined };
}

export function validateForgotPasswordBody(req) {
  const b = body(req);
  if (typeof b.email !== 'string' || b.email.trim().length === 0) throw invalid('email is required');
  const email = b.email.trim();
  if (email.length > LIMITS.emailMax || !EMAIL_RE.test(email)) throw invalid('email must be a valid email address');
  return { email };
}

export function validateResetPasswordBody(req) {
  const b = body(req);
  if (typeof b.token !== 'string' || b.token.length === 0) {
    throw new HttpError(400, 'invalid_token', 'Password reset token is invalid or has expired.');
  }
  if (typeof b.newPassword !== 'string' || b.newPassword.length === 0) {
    throw invalid('newPassword is required');
  }
  if (b.newPassword.length > LIMITS.passwordMax) throw invalid('password is too long');
  return { token: b.token, newPassword: b.newPassword };
}

export function validateDeleteAccountBody(req) {
  const b = body(req);
  if (b.password !== undefined && b.password !== null && typeof b.password !== 'string') {
    throw invalid('password must be a string when provided');
  }
  return { password: typeof b.password === 'string' ? b.password : undefined };
}
