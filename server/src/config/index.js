/**
 * Environment configuration + validation.
 *
 * Values are loaded with dotenv from <server>/.env when present (see
 * .env.example for the full documented list). loadConfig() accepts an explicit
 * env object so tests can run fully isolated from the developer's .env.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import dotenv from 'dotenv';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const SERVER_ROOT = path.resolve(HERE, '..', '..');

// Load <server>/.env (if present) into process.env exactly once. Missing file is fine.
dotenv.config({ path: path.join(SERVER_ROOT, '.env'), quiet: true });

/** Thrown when the environment is unusable (fail fast at startup). */
export class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

const DEV_JWT_SECRET = 'insecure-development-only-jwt-secret-do-not-use-in-production';

function intOr(env, key, fallback, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}) {
  const raw = env[key];
  if (raw === undefined || raw === null || String(raw).trim() === '') return fallback;
  const value = Number(String(raw).trim());
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new ConfigError(`${key} must be an integer between ${min} and ${max} (got ${JSON.stringify(raw)})`);
  }
  return value;
}

function flag(env, key, fallback = false) {
  const raw = env[key];
  if (raw === undefined || raw === null || String(raw).trim() === '') return fallback;
  const value = String(raw).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(value)) return true;
  if (['0', 'false', 'no', 'off'].includes(value)) return false;
  throw new ConfigError(`${key} must be a boolean flag (1/0, true/false)`);
}

function str(env, key, fallback = '') {
  const raw = env[key];
  if (raw === undefined || raw === null || String(raw).trim() === '') return fallback;
  return String(raw).trim();
}

function resolveDir(env, key, fallback) {
  const raw = str(env, key, fallback);
  return path.isAbsolute(raw) ? path.normalize(raw) : path.resolve(SERVER_ROOT, raw);
}

/**
 * Build the config object from an env bag.
 * @param {Record<string,string|undefined>} [env] defaults to process.env
 */
export function loadConfig(env = process.env) {
  const nodeEnv = str(env, 'NODE_ENV', 'development');
  const isProduction = nodeEnv === 'production';

  const port = intOr(env, 'PORT', 8080, { min: 0, max: 65535 });

  // Bind address. Loopback by default: this API is never meant to be reached
  // directly — nginx (same machine) fronts it over HTTPS on the Tailscale
  // MagicDNS hostname, so only the reverse proxy needs to talk to the port.
  const host = str(env, 'HOST', '127.0.0.1');
  if (host === '0.0.0.0' || host === '::') {
    if (isProduction) {
      throw new ConfigError('HOST must not expose every interface in production (use 127.0.0.1 behind the reverse proxy)');
    }
  }

  // JWT_SECRET is REQUIRED in production and must be strong enough.
  let jwtSecret = str(env, 'JWT_SECRET', '');
  if (isProduction) {
    if (!jwtSecret) {
      throw new ConfigError('JWT_SECRET is required when NODE_ENV=production (min 32 characters)');
    }
    if (jwtSecret.length < 32) {
      throw new ConfigError('JWT_SECRET must be at least 32 characters when NODE_ENV=production');
    }
  } else if (!jwtSecret) {
    // Development-only fallback; never valid for production.
    jwtSecret = DEV_JWT_SECRET;
  }

  const emailProvider = str(env, 'EMAIL_PROVIDER', 'console').toLowerCase();
  if (emailProvider !== 'console' && emailProvider !== 'smtp') {
    throw new ConfigError(`EMAIL_PROVIDER must be "console" or "smtp" (got ${JSON.stringify(emailProvider)})`);
  }
  const smtp = {
    HOST: str(env, 'SMTP_HOST', ''),
    PORT: intOr(env, 'SMTP_PORT', 587, { min: 1, max: 65535 }),
    SECURE: flag(env, 'SMTP_SECURE', false),
    USER: str(env, 'SMTP_USER', ''),
    PASS: str(env, 'SMTP_PASS', ''),
    FROM: str(env, 'SMTP_FROM', 'HomeNurse <no-reply@homenurse.local>'),
  };
  if (emailProvider === 'smtp' && !smtp.HOST) {
    throw new ConfigError('EMAIL_PROVIDER=smtp requires SMTP_HOST to be set');
  }

  const port_ = port;
  const publicBaseUrl = str(env, 'PUBLIC_BASE_URL', `http://localhost:${port_}`).replace(/\/+$/, '');

  const trustProxyRaw = str(env, 'TRUST_PROXY', '');
  let trustProxy = false;
  if (trustProxyRaw !== '') {
    if (/^\d+$/.test(trustProxyRaw)) trustProxy = Number(trustProxyRaw);
    else if (['1', 'true', 'yes', 'on'].includes(trustProxyRaw.toLowerCase())) trustProxy = true;
    else if (['0', 'false', 'no', 'off'].includes(trustProxyRaw.toLowerCase())) trustProxy = false;
    else trustProxy = trustProxyRaw; // express accepts 'loopback', CIDR lists, etc.
  }

  return Object.freeze({
    NODE_ENV: nodeEnv,
    isProduction,
    PORT: port_,
    HOST: host,
    PUBLIC_BASE_URL: publicBaseUrl,
    TRUST_PROXY: trustProxy,

    JWT_SECRET: jwtSecret,
    ACCESS_TOKEN_TTL: intOr(env, 'ACCESS_TOKEN_TTL', 900, { min: 60, max: 86400 }),
    REFRESH_TOKEN_TTL: intOr(env, 'REFRESH_TOKEN_TTL', 2592000, { min: 300, max: 31536000 }),
    RESET_TOKEN_TTL: intOr(env, 'RESET_TOKEN_TTL', 1800, { min: 60, max: 86400 }),

    GOOGLE_CLIENT_ID: str(env, 'GOOGLE_CLIENT_ID', ''),

    EMAIL_PROVIDER: emailProvider,
    SMTP: smtp,

    DATA_DIR: resolveDir(env, 'DATA_DIR', 'data'),
    MODELS_DIR: resolveDir(env, 'MODELS_DIR', 'models-store'),

    ADMIN_TOKEN: str(env, 'ADMIN_TOKEN', ''),
    RATE_LIMIT_DISABLED: flag(env, 'RATE_LIMIT_DISABLED', false),
    DEBUG_RESET_TOKENS: flag(env, 'DEBUG_RESET_TOKENS', false),
  });
}
