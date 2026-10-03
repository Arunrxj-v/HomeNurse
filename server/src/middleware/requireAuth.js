/**
 * Bearer access-token authentication.
 *
 * - Expects `Authorization: Bearer <jwt>`.
 * - Verifies the JWT (HS256) and the `typ: "access"` claim.
 * - Confirms the account still exists (deleted accounts lose access immediately).
 * - Sets req.user = { id, account } on success; throws 401 `unauthorized` otherwise.
 *
 * Access tokens only carry { sub, typ } — never email, username, or any
 * medical data.
 */
import { HttpError, asyncHandler } from '../util/http.js';

const UNAUTHORIZED = new HttpError(401, 'unauthorized', 'Authentication required.');

function bearerToken(req) {
  const header = req.headers.authorization;
  if (typeof header !== 'string') return null;
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return match ? match[1] : null;
}

export function createRequireAuth({ tokens, users }) {
  return asyncHandler(async (req, res, next) => {
    const token = bearerToken(req);
    if (!token) throw UNAUTHORIZED;

    const payload = tokens.verifyAccess(token); // throws 401 unauthorized
    const user = await users.findById(payload.sub);
    if (!user) throw UNAUTHORIZED;

    // Only the id is attached; internal records (which contain the password
    // hash) are never placed on the request object.
    req.user = { id: user.id };
    return next();
  });
}
