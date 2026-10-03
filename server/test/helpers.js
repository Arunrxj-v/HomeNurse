/**
 * Shared test helpers: ephemeral server on port 0, fetch wrappers, fixtures.
 */
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config/index.js';
import { createApp } from '../src/app.js';

const execFileAsync = promisify(execFile);

export const TEST_JWT_SECRET = 'test-only-jwt-secret-must-be-32-characters-long!!';
export const TEST_GOOGLE_CLIENT_ID = 'test-client-id.apps.googleusercontent.com';
export const PUBLISH_SCRIPT = fileURLToPath(new URL('../scripts/publish-model.js', import.meta.url));

/**
 * Start an app on an ephemeral port with isolated temp DATA_DIR/MODELS_DIR.
 * @param {Record<string,string>} [overrides] config env overrides
 * @param {{googleVerifier?: Function}} [options]
 */
export async function startServer(overrides = {}, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'homenurse-test-'));
  const dataDir = path.join(root, 'data');
  const modelsDir = path.join(root, 'models-store');
  const config = loadConfig({
    NODE_ENV: 'test',
    PORT: '0',
    PUBLIC_BASE_URL: 'https://api.test.example',
    JWT_SECRET: TEST_JWT_SECRET,
    GOOGLE_CLIENT_ID: TEST_GOOGLE_CLIENT_ID,
    DATA_DIR: dataDir,
    MODELS_DIR: modelsDir,
    RATE_LIMIT_DISABLED: '1',
    ...overrides,
  });

  const logs = [];
  const capture = (...args) => logs.push(args.map((a) => (typeof a === 'string' ? a : String(a))).join(' '));
  const logger = { info: capture, warn: capture, error: capture, log: capture, debug: capture };

  const app = createApp({ config, logger, googleVerifier: options.googleVerifier });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address();

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    config,
    app,
    server,
    logs,
    root,
    dataDir,
    modelsDir,
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(resolve));
      await fs.rm(root, { recursive: true, force: true }).catch(() => {});
    },
  };
}

async function readResponse(res) {
  const text = await res.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      /* not JSON */
    }
  }
  return { status: res.status, headers: res.headers, text, json };
}

export async function request(baseUrl, pathname, { method = 'GET', body, token, headers = {}, rawBody } = {}) {
  const init = { method, headers: { ...headers } };
  if (token) init.headers.authorization = `Bearer ${token}`;
  if (body !== undefined || rawBody !== undefined) {
    init.headers['content-type'] = init.headers['content-type'] ?? 'application/json';
    init.body = rawBody !== undefined ? rawBody : JSON.stringify(body);
  }
  return readResponse(await fetch(baseUrl + pathname, init));
}

export const postJson = (baseUrl, pathname, body, options = {}) =>
  request(baseUrl, pathname, { method: 'POST', body, ...options });

export const getJson = (baseUrl, pathname, options = {}) => request(baseUrl, pathname, { ...options });

/** Fetch that keeps the response as raw bytes (for model artifacts). */
export async function requestBuffer(baseUrl, pathname, { method = 'GET', token, headers = {} } = {}) {
  const init = { method, headers: { ...headers } };
  if (token) init.headers.authorization = `Bearer ${token}`;
  const res = await fetch(baseUrl + pathname, init);
  const buffer = Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, buffer };
}

export async function registerUser(server, { username = 'alice', email, password = 'Sup3rSecret!' } = {}) {
  const finalEmail = email ?? `${username}@example.com`;
  const res = await postJson(server.baseUrl, '/auth/register', { username, email: finalEmail, password });
  if (res.status !== 201) {
    throw new Error(`registerUser failed: ${res.status} ${res.text}`);
  }
  return { ...res.json, password };
}

/** Perform a raw HTTP request with an arbitrary (non-normalized) path. */
export function rawRequest(baseUrl, rawPath, method = 'GET', headers = {}) {
  const url = new URL(baseUrl);
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: url.hostname, port: url.port, path: rawPath, method, headers },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () =>
          resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }),
        );
      },
    );
    req.on('error', reject);
    req.end();
  });
}

/** Deterministic fixture content: byte i === (i * 7) % 256. */
export function fixtureBytes(length = 4096) {
  const buf = Buffer.alloc(length);
  for (let i = 0; i < length; i++) buf[i] = (i * 7) % 256;
  return buf;
}

/**
 * Publish a fixture model by invoking the real admin CLI (scripts/publish-model.js).
 * Returns the fixture content and CLI stdout.
 */
export async function publishFixture(
  server,
  {
    version = '1.0.0',
    modelId = 'homenurse-gemma',
    displayName = 'HomeNurse AI (Gemma 3 1B)',
    fileName = 'homenurse-gemma.litertlm',
    content = fixtureBytes(),
    lowEnd = false,
    extraArgs = [],
  } = {},
) {
  const staging = path.join(server.root, 'publish-input');
  await fs.mkdir(staging, { recursive: true });
  const file = path.join(staging, fileName);
  await fs.writeFile(file, content);

  const args = [
    PUBLISH_SCRIPT,
    '--file', file,
    '--version', version,
    '--model-id', modelId,
    '--display-name', displayName,
    '--out', server.modelsDir,
    ...extraArgs,
  ];
  if (lowEnd) args.push('--low-end');
  const { stdout } = await execFileAsync(process.execPath, args, { cwd: path.dirname(PUBLISH_SCRIPT) });
  return { file, content, stdout, version, modelId, displayName, fileName };
}
