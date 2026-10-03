/**
 * HomeNurse server entry point.
 *
 * Loads configuration (dotenv from server/.env), builds the app, listens.
 * Handles SIGINT/SIGTERM for a graceful shutdown.
 */
import { loadConfig, ConfigError } from './config/index.js';
import { createApp } from './app.js';

let config;
try {
  config = loadConfig();
} catch (err) {
  if (err instanceof ConfigError) {
    console.error(`Configuration error: ${err.message}`);
    process.exit(1);
  }
  throw err;
}

const app = createApp({ config, logger: console });

const server = app.listen(config.PORT, config.HOST, () => {
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : config.PORT;
  const host = typeof address === 'object' && address ? address.address : config.HOST;
  console.log(
    `HomeNurse server listening on ${host}:${port} ` +
      `(env=${config.NODE_ENV}, models=${config.MODELS_DIR}, email=${config.EMAIL_PROVIDER})`,
  );
  if (!config.isProduction && config.JWT_SECRET.startsWith('insecure-development')) {
    console.warn('WARNING: using the insecure development JWT_SECRET. Set JWT_SECRET in server/.env for anything real.');
  }
});

function shutdown(signal) {
  console.log(`${signal} received, shutting down...`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
