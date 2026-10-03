#!/usr/bin/env bash
#
# Tailnet-side verification for the HomeNurse backend.
# Run from ANY tailnet device (laptop, phone shell) — never from the server:
#
#     bash deploy/verify.sh [hostname] [port]
#
# Defaults to https://server.tailda589d.ts.net:8443/homenurse
#
# Covers the automated parts of the checklist in deploy/README.md:
#   [1] Tailscale reachable / MagicDNS name known
#   [2] MagicDNS resolves to a tailnet (100.64.0.0/10) address
#   [3] HTTPS certificate on the serve port validates with STOCK trust
#       (no -k, no --insecure anywhere in this script)
#   [5] GET <prefix>/health -> {"status":"ok"}
#   [b] API upstream port refuses direct connections (loopback-only bind)
#   [c] the public Funnel'd :443 host does NOT serve the HomeNurse API
#   [d] funnel is not enabled for the API's serve port (server-side only)
#
# Items 4/7/8/9 (app sign-in, model download, offline behaviour) are verified
# on the Android device itself.
set -uo pipefail

HOST="${1:-server.tailda589d.ts.net}"
PORT="${2:-8443}"
PREFIX="${PREFIX:-/homenurse}"
SCHEME="https"
UPSTREAM_PORT="${UPSTREAM_PORT:-8081}"

PASS=0
FAIL=0

# macOS ships no `timeout`; only use it where it exists.
if command -v timeout >/dev/null 2>&1; then
  T15="timeout 15"
  T5="timeout 5"
else
  T15=""
  T5=""
fi

ok()   { PASS=$((PASS + 1)); printf '  [OK]   %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '  [FAIL] %s\n' "$*"; }
skip() { printf '  [skip] %s\n' "$*"; }

echo "== HomeNurse backend verification: $SCHEME://$HOST:$PORT$PREFIX"

# --- [2] MagicDNS resolution -------------------------------------------------
IP="$(getent hosts "$HOST" 2>/dev/null | awk '{print $1; exit}')"
if [ -z "$IP" ]; then
  IP="$(python3 - "$HOST" <<'PY' 2>/dev/null
import socket, sys
try:
    print(socket.getaddrinfo(sys.argv[1], None, socket.AF_INET)[0][4][0])
except Exception:
    pass
PY
)"
fi

if [ -z "$IP" ]; then
  bad "[2] $HOST does not resolve (is Tailscale connected?)"
else
  case "$IP" in
    100.6[4-9].*|100.[7-9][0-9].*|100.1[0-1][0-9].*|100.12[0-7].*)
      ok "[2] $HOST -> $IP (tailnet address)" ;;
    *)
      bad "[2] $HOST -> $IP (NOT a tailnet 100.64.0.0/10 address — MagicDNS not in use?)" ;;
  esac
fi

# --- [1] tailscale CLI (optional, only meaningful where the CLI exists) ------
if command -v tailscale >/dev/null 2>&1; then
  if tailscale status 2>/dev/null | grep -q "$HOST"; then
    ok "[1] tailscale status lists $HOST"
  else
    bad "[1] tailscale status does not list $HOST"
  fi
else
  skip "[1] tailscale CLI not present on this machine (resolution check above still applies)"
fi

# --- [3] TLS with stock validation ------------------------------------------
CERT_INFO="$(echo | $T15 openssl s_client -connect "$HOST:$PORT" -servername "$HOST" 2>/dev/null)"
CERT_DATES="$(printf '%s' "$CERT_INFO" | openssl x509 -noout -dates 2>/dev/null)"
if [ -n "$CERT_DATES" ]; then
  ok "[3] certificate presented on :$PORT: $(printf '%s' "$CERT_DATES" | tr '\n' ' ')"
else
  bad "[3] no certificate presented on $HOST:$PORT"
fi

if printf '%s' "$CERT_INFO" | grep -qE "i:.*O = Internet Security Research Group|i:.*Let's Encrypt|CN = ISRG"; then
  ok "[3] chain anchors in the public trust store (ISRG/Let's Encrypt)"
else
  echo "  [note] issuer not recognised as ISRG in this output — the stock curl check below is authoritative"
fi

# Stock curl: any -k/--insecure here would invalidate the check by definition.
CURL_OUT="$(curl -sS -m 15 -o /dev/null -w '%{http_code} %{ssl_verify_result}' \
  "$SCHEME://$HOST:$PORT$PREFIX/health" 2>&1)"
HTTP_CODE="$(printf '%s' "$CURL_OUT" | awk '{print $1}')"
SSL_RC="$(printf '%s' "$CURL_OUT" | awk '{print $2}')"
if [ "$SSL_RC" = "0" ]; then
  ok "[3] stock TLS validation passed (ssl_verify_result=0, no -k used)"
else
  bad "[3] TLS validation failed (ssl_verify_result=$SSL_RC): $CURL_OUT"
fi

# --- [5] health ---------------------------------------------------------------
BODY="$(curl -sS -m 15 "$SCHEME://$HOST:$PORT$PREFIX/health" 2>/dev/null)"
if [ "$BODY" = '{"status":"ok"}' ]; then
  ok "[5] GET $PORT$PREFIX/health -> $BODY"
else
  bad "[5] GET $PORT$PREFIX/health -> '$BODY' (expected {\"status\":\"ok\"})"
fi

# --- [b] not directly exposed -------------------------------------------------
# The API binds loopback on the box, so reaching the upstream port on a
# routable address must fail. The tailnet address stands in for "public".
DIRECT_HOST="${DIRECT_HOST:-$IP}"
if [ -n "$DIRECT_HOST" ]; then
  if $T5 bash -c "exec 3<>/dev/tcp/$DIRECT_HOST/$UPSTREAM_PORT" 2>/dev/null; then
    bad "[b] $DIRECT_HOST:$UPSTREAM_PORT accepts connections directly — API is exposed"
  else
    ok "[b] $DIRECT_HOST:$UPSTREAM_PORT not reachable directly (loopback-only upstream)"
  fi
fi

# --- [c] the public host must not serve the API ------------------------------
# Port 443 on this box carries a Funnel'd (public) site. A request for the API
# prefix there must never reach the HomeNurse backend.
P443="$(curl -sS -m 15 "$SCHEME://$HOST$PREFIX/health" 2>/dev/null)"
if [ "$P443" = '{"status":"ok"}' ]; then
  bad "[c] $HOST:443$PREFIX/health serves the HomeNurse API — it is exposed on the funnel'd host"
else
  ok "[c] $HOST:443$PREFIX/health is not the HomeNurse API (public host serves something else)"
fi

# --- [d] funnel must be off for the API's port (server-side only) ------------
if command -v tailscale >/dev/null 2>&1; then
  SELF="$(tailscale status --json 2>/dev/null | python3 -c \
    'import json,sys; print(json.load(sys.stdin).get("Self",{}).get("DNSName","").lstrip("."))' 2>/dev/null)"
  if [ "$SELF" = "$HOST" ]; then
    if tailscale serve status --json 2>/dev/null | python3 -c "
import json, sys
cfg = json.load(sys.stdin)
sys.exit(0 if '$HOST:$PORT' not in cfg.get('AllowFunnel', {}) else 1)
"; then
      ok "[d] funnel not enabled for $HOST:$PORT (tailnet only)"
    else
      bad "[d] funnel is ENABLED for $HOST:$PORT — the API is public. Fix: tailscale funnel --https=$PORT off"
    fi
  else
    skip "[d] funnel config check only runs on the server itself (self='$SELF') — deploy/setup.sh performs it there"
  fi
else
  skip "[d] tailscale CLI not present"
fi

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
