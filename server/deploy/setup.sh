#!/usr/bin/env bash
#
# HomeNurse API installer — run ON the HomeServer, as the unprivileged user.
# No sudo is required: the Docker daemon does the process supervision and the
# Tailscale operator permission covers the serve configuration.
#
#     cd ~/homenurse/server && bash deploy/setup.sh
#
# What it does (idempotent):
#   1. verifies docker
#   2. writes ../server.env on first run (fresh JWT_SECRET, loopback bind)
#   3. builds the homenurse-api image and (re)creates the container with
#        --network host + HOST=127.0.0.1  ->  the API binds loopback ONLY,
#        so it can never be reached directly on a routable address
#   4. health-checks http://127.0.0.1:8081/health -> {"status":"ok"}
#   5. ensures the tailnet-only Tailscale serve listener on :8443 that fronts
#        it (HTTPS + MagicDNS + prefix strip), and REFUSES to continue if that
#        listener is funnel'd — the API must never be on the public internet
#
# HTTPS, certificate renewal and the /homenurse prefix are all handled by
# tailscaled (Tailscale's certificate for the MagicDNS name). There is no
# nginx, no systemd unit and no port forwarding on this box.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ROOT="$(dirname "$APP_DIR")"
ENV_FILE="$DEPLOY_ROOT/server.env"
DATA_DIR="$DEPLOY_ROOT/data"
MODELS_DIR="$DEPLOY_ROOT/models-store"

IMAGE="${IMAGE:-homenurse-api:latest}"
CONTAINER="${CONTAINER:-homenurse-api}"
UPSTREAM_PORT="${UPSTREAM_PORT:-8081}"
SERVE_PORT="${SERVE_PORT:-8443}"
HOSTNAME_TAILNET="${HOSTNAME_TAILNET:-server.tailda589d.ts.net}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://$HOSTNAME_TAILNET:$SERVE_PORT/homenurse}"

log() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

log "App dir: $APP_DIR (deploy root: $DEPLOY_ROOT)"

log "Verifying docker"
command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
docker version --format 'docker {{.Server.Version}}' >/dev/null 2>&1 ||
  die "cannot talk to the docker daemon (is this user in the docker group?)"

if [ ! -f "$ENV_FILE" ]; then
  log "Creating $ENV_FILE (first run)"
  umask 077
  cat > "$ENV_FILE" <<EOF
NODE_ENV=production
PORT=$UPSTREAM_PORT
HOST=127.0.0.1
PUBLIC_BASE_URL=$PUBLIC_BASE_URL
TRUST_PROXY=loopback
JWT_SECRET=$(openssl rand -hex 32)
ACCESS_TOKEN_TTL=900
REFRESH_TOKEN_TTL=2592000
RESET_TOKEN_TTL=1800
GOOGLE_CLIENT_ID=
EMAIL_PROVIDER=console
RATE_LIMIT_DISABLED=0
DEBUG_RESET_TOKENS=0
EOF
  chmod 600 "$ENV_FILE"
  echo "wrote server.env with a fresh JWT_SECRET (kept off-repo)"
else
  log "$ENV_FILE already exists — leaving it untouched"
fi

mkdir -p "$DATA_DIR" "$MODELS_DIR"

log "Building image"
docker build -t "$IMAGE" "$APP_DIR"

log "Recreating container (loopback-only bind)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d \
  --name "$CONTAINER" \
  --network host \
  --restart unless-stopped \
  --env-file "$ENV_FILE" \
  -v "$DATA_DIR:/app/data" \
  -v "$MODELS_DIR:/app/models-store" \
  "$IMAGE" >/dev/null

log "Health check on loopback"
for _ in $(seq 1 20); do
  if BODY="$(curl -fsS -m 3 "http://127.0.0.1:$UPSTREAM_PORT/health" 2>/dev/null)"; then
    echo "GET /health -> $BODY"
    case "$BODY" in
      *'"status":"ok"'*) echo "OK: API healthy on 127.0.0.1:$UPSTREAM_PORT";;
      *) die "unexpected health body: $BODY";;
    esac
    break
  fi
  sleep 1
done
[ -n "${BODY:-}" ] || die "API never became healthy on 127.0.0.1:$UPSTREAM_PORT"

log "Checking bind address"
BIND="$(ss -ltn "sport = :$UPSTREAM_PORT" 2>/dev/null | awk 'NR>1 {print $4}' | head -1)"
case "$BIND" in
  127.0.0.1:*|::1:*) echo "bind: $BIND (loopback only)";;
  "") echo "bind: (could not read; API responded on loopback)";;
  *) die "API is listening on $BIND — it must bind 127.0.0.1 only";;
esac

log "Tailscale serve listener (tailnet-only, port $SERVE_PORT)"
if ! command -v tailscale >/dev/null 2>&1; then
  echo "tailscale CLI not present — configure the listener manually (see deploy/README.md)"
else
  CFG="$(tailscale serve status --json 2>/dev/null || echo '{}')"
  if printf '%s' "$CFG" | grep -q "\"$HOSTNAME_TAILNET:$SERVE_PORT\""; then
    echo "listener already configured"
  else
    echo "adding: tailscale serve --bg --https=$SERVE_PORT --set-path=/homenurse http://127.0.0.1:$UPSTREAM_PORT/"
    if ! tailscale serve --bg --yes --https="$SERVE_PORT" --set-path=/homenurse \
        "http://127.0.0.1:$UPSTREAM_PORT/" >/dev/null 2>&1; then
      die "could not write the serve config — run once:
  sudo tailscale set --operator=\$(who -un | awk '{print \$1}')
then re-run this script."
    fi
  fi

  # Funnel is flagged per hostname:port, so the API must be absent from
  # AllowFunnel — otherwise the auth API would be on the public internet.
  if printf '%s' "$CFG" | python3 -c '
import json, sys
cfg = json.load(sys.stdin)
sys.exit(0 if "'"$HOSTNAME_TAILNET:$SERVE_PORT"'" not in cfg.get("AllowFunnel", {}) else 1)
' 2>/dev/null; then
    echo "funnel: NOT enabled for :$SERVE_PORT (correct — tailnet only)"
  else
    die "funnel is ENABLED for $HOSTNAME_TAILNET:$SERVE_PORT — the API would be public.
Disable it with:
  tailscale funnel --https=$SERVE_PORT off"
  fi
fi

cat <<'NEXT'

Next steps (see deploy/README.md):
  1. publish the model (already on this box, no download):
       bash deploy/publish-model.sh
  2. from any tailnet device:
       bash deploy/verify.sh
NEXT
