/**
 * /auth business logic.
 *
 * PRIVACY: this server handles account authentication only. Nothing in this
 * module (or any route it backs) accepts, stores, or returns medical data.
 */
import crypto from 'node:crypto';
import { HttpError } from '../util/http.js';
import { assertPasswordPolicy, hashPassword, verifyPassword, dummyVerify } from './passwords.js';
import { assertGooglePayload } from './google.js';
import { publicAccount } from '../users/store.js';

const INVALID_CREDENTIALS = new HttpError(401, 'invalid_credentials', 'Invalid username or password.');
const UNAUTHORIZED = new HttpError(401, 'unauthorized', 'Authentication required.');

function sanitizePart(value) {
  return String(value ?? '').toLowerCase().replace(/[^a-z0-9_.-]/g, '');
}

/**
 * @param {{
 *   config: object,
 *   users: ReturnType<import('../users/store.js').createUserStore>,
 *   tokens: ReturnType<import('./tokens.js').createTokenManager>,
 *   emailSender: {sendResetEmail(to: string, token: string): Promise<void>},
 *   verifyGoogleIdToken?: ((idToken: string) => Promise<object>) | null,
 *   logger?: Console,
 * }} deps
 */
export function createAuthService({
  config,
  users,
  tokens,
  emailSender,
  verifyGoogleIdToken = null,
  logger = console,
}) {
  async function issueTokens(user) {
    const session = await tokens.createSession(user.id);
    return {
      accessToken: tokens.issueAccess(user.id),
      refreshToken: session.raw,
      accessTokenExpiresIn: config.ACCESS_TOKEN_TTL,
      refreshTokenExpiresIn: config.REFRESH_TOKEN_TTL,
    };
  }

  async function deriveUniqueUsername(payload) {
    const email = typeof payload.email === 'string' ? payload.email : '';
    const local = sanitizePart(email.split('@')[0]);
    const subPart = sanitizePart(payload.sub).slice(0, 20);
    const candidates = [];
    if (local.length >= 3) candidates.push(local.slice(0, 26));
    else if (local.length > 0) candidates.push(`${local}user`.slice(0, 26));
    if (subPart.length > 0) {
      candidates.push(`google_${subPart}`.slice(0, 32));
      candidates.push(`g_${subPart}`.slice(0, 32));
    }
    for (const base of candidates) {
      if (base.length < 3) continue;
      for (let i = 0; i < 50; i++) {
        const candidate = i === 0 ? base : `${base}_${i + 1}`.slice(0, 32);
        if (!(await users.usernameTaken(candidate))) return candidate;
      }
    }
    return `google_${subPart.slice(0, 12) || 'user'}_${crypto.randomBytes(3).toString('hex')}`;
  }

  return {
    /** POST /auth/register -> 201 { account, tokens } */
    async register({ username, email, password }) {
      // Conflict checks first (cheap), then policy, then hash + atomic insert.
      if (await users.usernameTaken(username)) {
        throw new HttpError(409, 'username_taken', 'That username is already taken.');
      }
      if (await users.emailTaken(email)) {
        throw new HttpError(409, 'email_taken', 'That email is already registered.');
      }
      assertPasswordPolicy(password, { username, email });
      const passwordHash = await hashPassword(password);
      const user = await users.create({ username, email, passwordHash, provider: 'local' });
      return { account: publicAccount(user), tokens: await issueTokens(user) };
    },

    /** POST /auth/login -> 200 { account, tokens } (no user enumeration) */
    async login({ usernameOrEmail, password }) {
      const user = usernameOrEmail.includes('@')
        ? await users.findByEmail(usernameOrEmail)
        : await users.findByUsername(usernameOrEmail);

      if (!user || !user.passwordHash) {
        // Constant-ish work for unknown accounts: still verify against a dummy hash.
        await dummyVerify(password);
        throw INVALID_CREDENTIALS;
      }
      const ok = await verifyPassword(user.passwordHash, password);
      if (!ok) throw INVALID_CREDENTIALS;
      return { account: publicAccount(user), tokens: await issueTokens(user) };
    },

    /** POST /auth/google -> 200 { account, tokens } (creates account on first sign-in) */
    async googleLogin({ idToken, nonce }) {
      if (!config.GOOGLE_CLIENT_ID || typeof verifyGoogleIdToken !== 'function') {
        throw new HttpError(501, 'google_not_configured', 'Google sign-in is not configured on this server.');
      }

      let payload;
      try {
        payload = await verifyGoogleIdToken(idToken);
      } catch (err) {
        if (err instanceof HttpError) throw err;
        throw new HttpError(401, 'invalid_google_token', 'Google ID token verification failed.');
      }
      assertGooglePayload(payload, { clientId: config.GOOGLE_CLIENT_ID, nonce });

      let user = await users.findByGoogleSub(payload.sub);
      const email = typeof payload.email === 'string' && payload.email.length > 0 ? payload.email : null;
      const emailVerified = payload.email_verified !== false;
      if (!user) {
        if (email) {
          const existing = await users.findByEmail(email);
          if (existing) {
            if (existing.provider === 'local' && payload.email_verified === true && !existing.googleSub) {
              // Verified Google email matching a local account: link the identities.
              user = await users.update(existing.id, { googleSub: payload.sub });
            } else {
              throw new HttpError(409, 'email_taken', 'That email is already registered.');
            }
          }
        }
      }
      if (!user) {
        user = await users.create({
          username: await deriveUniqueUsername(payload),
          email,
          provider: 'google',
          googleSub: payload.sub,
          emailVerified,
        });
      }
      return { account: publicAccount(user), tokens: await issueTokens(user) };
    },

    /** POST /auth/refresh -> 200 { tokens } (one-time use, family revocation on reuse) */
    async refresh(refreshToken) {
      const rotated = await tokens.rotate(refreshToken);
      const user = await users.findById(rotated.userId);
      if (!user) {
        await tokens.purgeUserTokens(rotated.userId);
        throw tokens.errors.INVALID_SESSION;
      }
      return {
        tokens: {
          accessToken: tokens.issueAccess(user.id),
          refreshToken: rotated.refreshToken,
          accessTokenExpiresIn: config.ACCESS_TOKEN_TTL,
          refreshTokenExpiresIn: config.REFRESH_TOKEN_TTL,
        },
      };
    },

    /** POST /auth/logout -> 204 always (idempotent, reveals nothing). */
    async logout(refreshToken) {
      await tokens.revoke(refreshToken);
    },

    /** POST /auth/forgot-password -> 202 always, regardless of email existence. */
    async forgotPassword(email) {
      const user = await users.findByEmail(email);
      if (!user || !user.email) return;
      const { raw } = await tokens.issueResetToken(user.id);
      try {
        await emailSender.sendResetEmail(user.email, raw);
      } catch (err) {
        // Never fail (and never leak) because of a delivery problem.
        logger.error?.(`password-reset delivery failed: ${err.message}`);
      }
    },

    /** POST /auth/reset-password -> 200 { ok: true }; invalid/expired/used -> 400 invalid_token */
    async resetPassword({ token, newPassword }) {
      const userId = await tokens.peekResetToken(token); // 400 invalid_token when bad
      const user = await users.findById(userId);
      if (!user) throw tokens.errors.INVALID_RESET_TOKEN;
      // Policy check before consuming the single-use token (better UX).
      assertPasswordPolicy(newPassword, { username: user.username, email: user.email });
      const consumed = await tokens.consumeResetToken(token).then(() => true).catch(() => false);
      if (!consumed) throw tokens.errors.INVALID_RESET_TOKEN;
      const passwordHash = await hashPassword(newPassword);
      await users.update(user.id, { passwordHash });
      // A password change invalidates every existing session.
      await tokens.revokeUserSessions(user.id);
      return { ok: true };
    },

    /** GET /auth/me -> 200 { account } (never contains hash/password fields) */
    async me(userId) {
      const user = await users.findById(userId);
      if (!user) throw UNAUTHORIZED;
      return { account: publicAccount(user) };
    },

    /** POST /auth/delete-account -> 200 { ok: true } */
    async deleteAccount(userId, password) {
      const user = await users.findById(userId);
      if (!user) throw UNAUTHORIZED;
      if (user.provider === 'local') {
        if (typeof password !== 'string' || password.length === 0) {
          throw new HttpError(400, 'password_required', 'password is required to delete this account.');
        }
        const ok = await verifyPassword(user.passwordHash, password);
        if (!ok) throw new HttpError(401, 'invalid_credentials', 'Password is incorrect.');
      }
      await users.remove(userId);
      await tokens.purgeUserTokens(user.id);
      return { ok: true };
    },
  };
}
