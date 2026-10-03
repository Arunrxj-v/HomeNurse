# syntax=docker/dockerfile:1
# HomeNurse auth / model-distribution API for the HomeServer.
#
# Deployment contract (see deploy/README.md):
#   docker run --network host ...  with HOST=127.0.0.1
# so the API listens ONLY on the server's loopback interface. Tailscale serve
# is the single ingress: HTTPS on the MagicDNS name, tailnet-only (never
# funnelled). The container itself has no published ports — the host network
# plus the loopback bind is the exposure boundary.
FROM node:22-alpine

WORKDIR /app

# Production dependencies only; npm ci requires the lockfile to match.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force

# Runtime payload: the API, the admin-only publisher CLI (there is
# intentionally no HTTP upload endpoint for models) and the Gemma notice that
# publish-model.js copies next to every artifact.
COPY src ./src
COPY scripts ./scripts
COPY LICENSES ./LICENSES

ENV NODE_ENV=production \
    HOST=127.0.0.1 \
    PORT=8081 \
    DATA_DIR=/app/data \
    MODELS_DIR=/app/models-store

EXPOSE 8081

USER node

CMD ["node", "src/server.js"]
