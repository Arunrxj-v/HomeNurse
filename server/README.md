# HomeNurse Server

Backend for the HomeNurse Android app. **Plain ESM JavaScript on Node.js ≥ 20,
Express, no build step.**

## Privacy statement (hard product requirement)

> This server handles **account authentication** and **model file
> distribution** — nothing else. There are **no medical-data endpoints of any
> kind**: no document upload, no OCR, no patient/sync, no chats, no prompts,
> no medical payloads are ever accepted or stored. The complete route surface
> is enforced by an automated test (`test/route-allowlist.test.js`) that fails
> if any route outside the allowlist is ever added.
>
> The server-side data stores contain only: account records (username, email,
> argon2id password hash), opaque token digests, and the published model
> manifest/artifacts. No request bodies or `Authorization` values are ever
> written to the logs.

## Architecture

```
server/
  src/
    server.js              entry point (dotenv -> config -> listen)
    app.js                 express app factory (used by the app and by tests)
    config/index.js        env config + validation (fails fast on bad config)
    middleware/
      security.js          security headers + 64kb JSON body limit
      rateLimit.js         in-memory sliding-window limiter (ip + route class)
      requestLog.js        method/path/status/duration/ip — never bodies/tokens
      errorHandler.js      uniform {"error":{code,message}} envelope, JSON 404
      requireAuth.js       Bearer access-JWT verification -> req.user
    auth/
      routes.js            all /auth/* endpoints
      service.js           business logic
      passwords.js         argon2id (m=19456,t=2,p=1) + fallback chain + policy
      tokens.js            access JWT, opaque refresh tokens (SHA-256 at rest),
                           rotation/family revocation, reset tokens
      google.js            Google ID-token verification (injectable for tests)
      validation.js        server-side input validation
      emails.js            pluggable reset-email transport (console | smtp)
    users/store.js         user repository over the JSON-file store
    models/
      routes.js            /models/manifest + /models/:version/:filename
      store.js             published manifest + safe path resolution
    util/jsonStore.js      atomic JSON persistence (tmp + rename, serialized)
    util/http.js           sendJson / error envelope / helpers
  scripts/publish-model.js admin CLI (the only way artifacts are published)
  LICENSES/GEMMA_NOTICE.txt  Gemma notice, copied next to every artifact
  test/                    node:test suites
  data/                    runtime JSON db (gitignored)
  models-store/            published artifacts + manifest (gitignored)
```

### Token model

| Token | Form | Storage | Lifetime |
| --- | --- | --- | --- |
| Access | JWT HS256, claims `{sub, typ:"access"}` | not stored | `ACCESS_TOKEN_TTL` (900 s) |
| Refresh | opaque 32-byte base64url | **SHA-256 hex only** | `REFRESH_TOKEN_TTL` (30 d) |
| Password reset | opaque 32-byte base64url | **SHA-256 hex only** | `RESET_TOKEN_TTL` (1800 s), single use |

Refresh rotation is **one-time use**. Presenting an already-rotated token
revokes the entire token family (refresh-token theft detection). A password
reset revokes all of the account's refresh tokens. Deleting an account removes
the user and all of their token records.

## Endpoints (the complete allowlist)

All errors are `HTTP status` + `{"error":{"code":"...","message":"..."}}`.

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | `/health` | — | `200 {"status":"ok"}` |
| POST | `/auth/register` | — | 201 `{account, tokens}` — 400 `invalid_input`/`weak_password`, 409 `username_taken`/`email_taken` |
| POST | `/auth/login` | — | 200 `{account, tokens}` — 401 `invalid_credentials` (identical body for unknown user vs wrong password) |
| POST | `/auth/google` | — | 200 `{account, tokens}` — 401 `invalid_google_token`, 501 `google_not_configured` |
| POST | `/auth/refresh` | — | 200 `{tokens}` — 401 `invalid_session` (reuse ⇒ family revoked) |
| POST | `/auth/logout` | — | 204 always, idempotent |
| POST | `/auth/forgot-password` | — | 202 `{"ok":true}` always, regardless of existence |
| POST | `/auth/reset-password` | — | 200 `{"ok":true}` — 400 `invalid_token`/`weak_password` |
| GET | `/auth/me` | Bearer access JWT | 200 `{account}` — never contains hash/password fields |
| POST | `/auth/delete-account` | Bearer access JWT | 200 `{"ok":true}` — 400 `password_required` (local), 401 `invalid_credentials` |
| GET | `/models/manifest` | Bearer access JWT | exact manifest shape below — 404 `model_not_published` |
| GET/HEAD | `/models/:version/:filename` | Bearer access JWT | streamed bytes, `Range` → 206, bad range → 416, traversal → 400 |

Anything else is a JSON 404.

### Rate limits (per IP + route class, in-memory)

| Route class | Limit |
| --- | --- |
| login | 10 / 15 min |
| register | 5 / hour |
| google | 10 / 15 min |
| forgot-password | 5 / hour |
| reset-password | 10 / hour |
| refresh | 60 / 15 min |
| models manifest | 120 / min |
| model file | 300 / hour (resumable downloads) |

429 responses use code `rate_limited` and carry `Retry-After` (seconds).
Set `RATE_LIMIT_DISABLED=1` to disable everything (the test suite does).

### Manifest shape (served by `GET /models/manifest`)

```json
{
  "modelId": "homenurse-gemma",
  "version": "1.0.0",
  "filename": "homenurse-gemma.litertlm",
  "sizeBytes": 123456789,
  "sha256": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
  "downloadUrl": "https://api.homenurse.example/models/1.0.0/homenurse-gemma.litertlm",
  "runtime": "litert-lm",
  "minimumAndroidVersion": "26",
  "minimumRamMb": 3072,
  "displayName": "HomeNurse AI (Gemma 3 1B)",
  "lowEndDevice": false,
  "licenseUrl": "https://ai.google.dev/gemma/terms",
  "noticeUrl": "https://api.homenurse.example/models/1.0.0/NOTICE"
}
```

`downloadUrl` / `noticeUrl` are derived at serve time from
`PUBLIC_BASE_URL`; the stored `MODELS_DIR/manifest.json` holds the metadata
(`modelId`, `version`, `filename`, `sizeBytes`, `sha256`, `runtime`,
`minimumAndroidVersion`, `minimumRamMb`, `displayName`, `lowEndDevice`,
`licenseUrl`, `updatedAt`).

## Configuration

Copy `.env.example` to `.env` and edit. `.env` is gitignored — never commit
secrets. Values are documented inline in `.env.example`; highlights:

| Variable | Default | Notes |
| --- | --- | --- |
| `PORT` | 8080 | |
| `PUBLIC_BASE_URL` | `http://localhost:$PORT` | builds `downloadUrl` / `noticeUrl` |
| `JWT_SECRET` | dev fallback | **required in production, min 32 chars** |
| `ACCESS_TOKEN_TTL` | 900 | seconds |
| `REFRESH_TOKEN_TTL` | 2592000 | seconds (30 days) |
| `RESET_TOKEN_TTL` | 1800 | seconds (30 minutes) |
| `GOOGLE_CLIENT_ID` | unset | unset ⇒ `POST /auth/google` → 501 |
| `EMAIL_PROVIDER` | `console` | `console` \| `smtp` (smtp needs `SMTP_HOST`) |
| `DATA_DIR` / `MODELS_DIR` | `./data` / `./models-store` | |
| `RATE_LIMIT_DISABLED` | 0 | |
| `DEBUG_RESET_TOKENS` | 0 | logs the raw reset token — **never in production** |
| `TRUST_PROXY` | off | needed for `req.secure`/HSTS behind a TLS proxy |

## Run

```bash
cd server
npm install                # argon2 native module included; see .npmrc note below
cp .env.example .env      # then edit
npm start                 # node src/server.js
```

> `server/.npmrc` points npm's cache at a gitignored project-local
> `.npm-cache/` directory, so installs keep working even when the machine's
> global `~/.npm` cache is unusable (e.g. root-owned files → `EACCES`).

## Test

```bash
cd server
npm test
# runs: node --test "test/*.test.js"
```

> The spec'd `node --test test/` form does not work on Node 22.14: this
> version treats positional test-runner arguments as glob patterns and then
> tries to execute a matched directory as a file (`MODULE_NOT_FOUND`). The
> quoted `test/*.test.js` glob is equivalent and portable.

Tests start real servers on ephemeral ports (`app.listen(0)`) with isolated
temporary `DATA_DIR`/`MODELS_DIR`, drive them with the global `fetch`, and
cover: the route allowlist + forbidden medical endpoints, the full auth
lifecycle (register → login → me → refresh rotation → reuse ⇒ family revocation
→ logout), credential-error uniformity, validation/conflict codes, argon2id
storage semantics, password reset (202-always, expiry, single use, session
revocation, log redaction), mocked Google sign-in (incl. nonce and issuer/aud/exp
checks), the login rate limiter, request-log privacy, the manifest contract,
downloads with `Range`/`HEAD`, and path-traversal defense.

## Publishing a model (admin, on the server host — no HTTP upload exists)

```bash
node scripts/publish-model.js \
  --file /secure/path/homenurse-gemma.litertlm \
  --version 1.0.0 \
  --model-id homenurse-gemma \
  --display-name "HomeNurse AI (Gemma 3 1B)" \
  --min-ram-mb 3072 --min-android 26 --runtime litert-lm \
  --out "$MODELS_DIR"
```

What it does:

1. Streams the artifact into `MODELS_DIR/<version>/<filename>` while computing
   SHA-256 (or verifies a supplied `--sha256`). Refuses to overwrite an
   existing version unless `--force`.
2. Writes `MODELS_DIR/<version>/NOTICE` — a byte-for-byte copy of
   `LICENSES/GEMMA_NOTICE.txt`.
3. Updates `MODELS_DIR/manifest.json` (atomic tmp + rename) so this version is
   the one the server serves.

## Gemma license compliance

Gemma model files are distributed under the Gemma Terms of Use. The steps this
server enforces:

1. `LICENSES/GEMMA_NOTICE.txt` ships with the server. It contains the required
   sentence — *"Gemma is provided under and subject to the Gemma Terms of Use
   found at ai.google.dev/gemma/terms"* — states that recipients receive a
   copy of the Gemma Terms of Use (<https://ai.google.dev/gemma/terms>), and
   notifies recipients that use is subject to the restrictions in Section 3.2
   and the Gemma Prohibited Use Policy
   (<https://ai.google.dev/gemma/prohibited_use_policy>), which are
   incorporated by reference. It makes no claim of Google endorsement.
2. `scripts/publish-model.js` **fails** if the notice file is missing or no
   longer contains the required sentence, and copies it to
   `MODELS_DIR/<version>/NOTICE` next to every published artifact.
3. The served manifest exposes `licenseUrl` and `noticeUrl`
   (`<PUBLIC_BASE_URL>/models/<version>/NOTICE`) so clients can retrieve and
   display the notice at download time.
4. The test suite asserts the notice content and both manifest URLs.

## Security notes & known trade-offs

- Passwords: argon2id, `m=19456, t=2, p=1`, 16-byte random salt, encoded
  string stored. If the native `argon2` module cannot be installed, the code
  falls back to `@node-rs/argon2`, then `hash-wasm`, then `node:crypto`
  scrypt (`N=32768, r=8, p=1, keylen=64`) — `verify()` dispatches on the
  stored string's prefix, so hashes survive a backend change.
- Login never reveals whether an account exists (identical 401 body, dummy
  hash comparison for unknown users).
- `SMTP` delivery uses `nodemailer`; if it is unavailable the transport throws
  `smtp_not_configured: …`, which is logged while forgot-password still
  answers 202 (delivery problems are never leaked to the caller).
- Rate-limit state is in memory: per-process, resets on restart. Put the
  service behind a reverse proxy with its own limit for multi-node setups.
- The access log intentionally contains no query strings, headers, or bodies.
- Error responses never contain stack traces; production 5xx responses are
  reduced to `internal_error` / "Internal server error".
