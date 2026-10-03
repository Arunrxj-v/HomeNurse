#!/usr/bin/env bash
#
# Publish the Gemma artifact that ALREADY EXISTS on the HomeServer.
#
#     bash deploy/publish-model.sh
#
# There is no download step anywhere: no Hugging Face, no token, no third-party
# model host. scripts/publish-model.js is the only way artifacts enter the
# server (there is intentionally no HTTP upload endpoint) — it streams the
# file into the model store while computing its SHA-256 and fails unless it
# matches the expected digest, then writes manifest.json + the Gemma notice.
#
# The artifact is never moved, edited or committed to Git; it stays on the box.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ROOT="$(dirname "$APP_DIR")"
ENV_FILE="$DEPLOY_ROOT/server.env"
MODELS_DIR="$DEPLOY_ROOT/models-store"

IMAGE="${IMAGE:-homenurse-api:latest}"
CONTAINER="${CONTAINER:-homenurse-api}"

# The model already present on this box (override to point elsewhere).
SOURCE_DIR="${SOURCE_DIR:-/opt/homenurse/models}"
SOURCE_MODEL="${SOURCE_MODEL:-$SOURCE_DIR/gemma-4-E2B-it.litertlm}"

MODEL_ID="${MODEL_ID:-homenurse-gemma-4-e2b}"
VERSION="${VERSION:-1.0.0}"
DISPLAY_NAME="${DISPLAY_NAME:-HomeNurse AI (Gemma 4 E2B)}"
MIN_RAM_MB="${MIN_RAM_MB:-6144}"
MIN_ANDROID="${MIN_ANDROID:-26}"
# Set FORCE=1 to replace an already-published version directory (upgrades).
FORCE="${FORCE:-0}"

# SHA-256 of the artifact above, computed on this box with sha256sum. Passing
# it makes the publisher refuse anything else. NOTE: a previously circulated
# copy of this value was 62 characters long and therefore invalid; a SHA-256
# is always exactly 64 hex characters.
EXPECTED_SHA256="${EXPECTED_SHA256:-181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ -f "$SOURCE_MODEL" ] || die "model not found: $SOURCE_MODEL (no download is performed by design)"
[ -f "$ENV_FILE" ] || die "$ENV_FILE missing — run deploy/setup.sh first"
command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "image $IMAGE missing — run deploy/setup.sh first"

printf '== publishing %s (version %s)\n' "$SOURCE_MODEL" "$VERSION"
echo "   expected sha256: $EXPECTED_SHA256"

EXTRA_ARGS=()
if [ "$FORCE" = "1" ]; then EXTRA_ARGS+=(--force); fi

docker run --rm --network host --env-file "$ENV_FILE" \
  -v "$MODELS_DIR:/app/models-store" \
  -v "$SOURCE_DIR:$SOURCE_DIR:ro" \
  "$IMAGE" node scripts/publish-model.js \
    --file "$SOURCE_MODEL" \
    --model-id "$MODEL_ID" \
    --version "$VERSION" \
    --display-name "$DISPLAY_NAME" \
    --min-ram-mb "$MIN_RAM_MB" \
    --min-android "$MIN_ANDROID" \
    --runtime litert-lm \
    --sha256 "$EXPECTED_SHA256" \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}

printf '\n== manifest\n'
cat "$MODELS_DIR/manifest.json"

# Restart the API so it serves the freshly written manifest without a stale
# in-process cache.
if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  printf '\n== restarting %s to pick up the new manifest\n' "$CONTAINER"
  docker restart "$CONTAINER" >/dev/null
  sleep 2
fi
