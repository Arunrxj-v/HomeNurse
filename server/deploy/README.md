# HomeNurse backend — HomeServer deployment

## Architecture (required, not optional)

```
Android Phone
      |
      | Tailscale encrypted network (MagicDNS: server.tailda589d.ts.net)
      v
Linux HomeServer  tailscale serve :8443  (HTTPS, Tailscale-managed
      |            Let's Encrypt cert, /homenurse prefix stripped)
      | http://127.0.0.1:8081  (loopback only — never exposed)
      v
HomeNurse API in Docker (auth + model distribution)
```

* **Base URL**: `https://server.tailda589d.ts.net:8443/homenurse` — the box's
  Tailscale MagicDNS name on a **dedicated tailnet-only serve listener**.
  It resolves only inside the tailnet and only `tailscaled` answers on that
  port, so the API is unreachable from the public internet.
* **Why port 8443 and not 443**: port 443 already carries a Funnel'd (public)
  site on this box, and Tailscale flags funnel per *hostname:port* rather than
  per path — `AllowFunnel` is a single boolean for
  `server.tailda589d.ts.net:443`. Sharing 443 would therefore have made the
  auth API public too. The API gets its own listener with **no** `AllowFunnel`
  entry; the existing Funnel on `:443` stays untouched.
* **TLS**: Tailscale provisions and renews the publicly trusted Let's Encrypt
  certificate for the MagicDNS name, so Android validates it with stock TLS
  checks. Never disable verification, never pin a self-signed cert.
* **Prefix stripping**: `tailscale serve --set-path=/homenurse` forwards
  `/homenurse/auth/login` to the app as `/auth/login` (verified: the app log
  shows `GET /health`, not `GET /homenurse/health`), so the API keeps clean
  root-relative routes.
* **Not used, ever**: `localhost`, `127.0.0.1`, LAN IP, public IP, port
  forwarding, plain HTTP (production), direct public exposure of the API.
* **The Mac is only the development workstation** — nothing depends on it.

## Network responsibility

| HomeServer | Android (never leaves the phone) |
|---|---|
| Authentication API | Medical data, documents, OCR |
| Google authentication backend | Room/SQLCipher database |
| Account management (register/login/refresh/logout/reset) | Gemma model + inference |
| Model manifest | AI conversations, care plans, medicines |
| Gemma model download (Range/resumable) | Safety engine |

The HomeServer must never receive medical data — enforced by the app's
network-isolation tests (`NetworkIsolationTest`, `OfflineBehaviorTest`).

## Install / upgrade (no sudo required)

```bash
# on the HomeServer, as the unprivileged user
cd ~/homenurse/server && bash deploy/setup.sh
```

`setup.sh` is idempotent and:

1. verifies Docker,
2. writes `../server.env` on first run (fresh `JWT_SECRET`, `HOST=127.0.0.1`,
   `PUBLIC_BASE_URL=https://server.tailda589d.ts.net:8443/homenurse`),
3. builds the image and recreates the container with
   `--network host` + `HOST=127.0.0.1`, so the process binds **loopback only**
   (Docker is the supervisor; there is no systemd unit),
4. health-checks `GET http://127.0.0.1:8081/health` → `{"status":"ok"}` and
   asserts the bind address is loopback,
5. ensures the `:8443` tailnet-only serve listener exists and **refuses to
   finish** if that port is funnel'd.

Only step 5 can need privileges, and only once:

```bash
sudo tailscale set --operator=$(who -un | awk '{print $1}')
```

TLS, certificate renewal and the `/homenurse` prefix are handled entirely by
`tailscaled` — there is no nginx, no systemd unit and no port forwarding.

### Model publishing

```bash
cd ~/homenurse/server && bash deploy/publish-model.sh
```

The artifact already lives on the box at
`/opt/homenurse/models/gemma-4-E2B-it.litertlm` — **nothing is ever
downloaded** (no Hugging Face, no token, no third-party host). The script runs
`scripts/publish-model.js` inside the container, which streams the file into
`models-store/`, computes its SHA-256, fails unless it equals
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
(64 hex characters — a value of 62 characters circulated earlier and is
invalid by definition), then writes `manifest.json` plus the verbatim Gemma
notice. The source file is untouched and never committed to Git.

The Android app refuses any download that lacks a server-provided SHA-256 and
verifies the bytes before install.

## Verification checklist

Run from a tailnet device (phone or laptop), not from the server itself:

```bash
bash deploy/verify.sh          # automates [1][2][3][5][b][c][d]
```

1. Tailscale connected on the HomeServer — `tailscale status`
2. MagicDNS resolves on the Android device — `ping server.tailda589d.ts.net`
   → `100.104.179.69`
3. HTTPS certificate valid — `curl -v https://server.tailda589d.ts.net:8443/homenurse/health`
   (no `-k`)
4. Android reaches the server (app sign-in over the tailnet)
5. `curl https://server.tailda589d.ts.net:8443/homenurse/health` → `{"status":"ok"}`
6. Everything still works with the Mac powered off
7. Android authenticates through Tailscale (register + sign in)
8. Gemma model downloads through the same HTTPS endpoint (Range/resume,
   SHA-256 verified in-app)
9. With Tailscale/network off after install: documents, OCR, medicines, care
   plans, chat, safety all work locally — no server request is attempted

The script additionally asserts that the public Funnel'd `:443` host does **not**
serve this API and that `:8443` has no funnel flag.

Do not mark the network implementation complete until all nine pass.

## Offline rule

After authentication and model install, the app must perform **no** network
request for medical functionality: viewing documents, OCR, medicines, care
plans, asking questions, Gemma inference, medical history, local safety
processing. Tailscale/HTTPS is needed only for authentication, Google sign-in,
password reset, model download/update, and optional account operations.
