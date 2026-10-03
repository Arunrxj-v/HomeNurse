/**
 * Google Sign-In ID-token verification.
 *
 * The real verifier uses google-auth-library's OAuth2Client.verifyIdToken,
 * which checks the signature, issuer and audience. On top of that we apply
 * our own explicit payload checks (defense in depth, and unit-testable):
 *
 *  - `iss` must be accounts.google.com (or https://accounts.google.com).
 *    securetoken@gserviceaccount.com is explicitly NOT accepted.
 *  - `aud` must match GOOGLE_CLIENT_ID.
 *  - `exp` must not be expired.
 *  - when the client sent a raw `nonce`, the payload `nonce` claim must equal
 *    SHA-256 hex of that raw value (computed server-side).
 *
 * The verifier is injectable so tests can mock Google.
 */
import crypto from 'node:crypto';
import { OAuth2Client } from 'google-auth-library';
import { HttpError } from '../util/http.js';

const ACCEPTED_ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

function invalidToken(message = 'Google ID token verification failed.') {
  return new HttpError(401, 'invalid_google_token', message);
}

export function sha256hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

/**
 * Validate a decoded Google ID-token payload.
 * @param {object} payload decoded JWT claims
 * @param {{clientId: string, nonce?: string, now?: number}} options
 */
export function assertGooglePayload(payload, { clientId, nonce, now = Date.now() }) {
  if (!payload || typeof payload !== 'object') throw invalidToken();

  // Issuer: only Google's OIDC issuer for Google ID tokens.
  if (!ACCEPTED_ISSUERS.has(payload.iss)) throw invalidToken('Unexpected token issuer.');

  // Audience must match the configured client id exactly.
  const aud = payload.aud;
  const audienceOk = Array.isArray(aud) ? aud.includes(clientId) : aud === clientId;
  if (!audienceOk) throw invalidToken('Token audience does not match this server.');

  // Expiry.
  if (typeof payload.exp !== 'number' || payload.exp <= 0) throw invalidToken('Token has no expiry.');
  if (now >= payload.exp * 1000) throw invalidToken('Token has expired.');

  // Subject.
  if (typeof payload.sub !== 'string' || payload.sub.length === 0) throw invalidToken('Token has no subject.');

  // Nonce binding (client sends the raw nonce, Google echoes it back; the
  // server requires the payload value to be SHA-256 hex of the raw nonce).
  if (nonce !== undefined && nonce !== null) {
    if (typeof payload.nonce !== 'string' || payload.nonce !== sha256hex(nonce)) {
      throw invalidToken('Nonce mismatch.');
    }
  }

  return payload;
}

/**
 * Build the default verifier backed by google-auth-library.
 * Returns null when GOOGLE_CLIENT_ID is not configured.
 */
export function createGoogleVerifier({ googleClientId }) {
  if (!googleClientId) return null;
  const client = new OAuth2Client(googleClientId);
  return async function verifyGoogleIdToken(idToken) {
    const ticket = await client.verifyIdToken({ idToken, audience: googleClientId });
    const payload = ticket.getPayload();
    if (!payload) throw invalidToken();
    return payload;
  };
}
