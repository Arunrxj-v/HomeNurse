/**
 * PRIVACY ENFORCEMENT: the server may expose ONLY the routes on this
 * allowlist (account authentication + model file distribution + health).
 * Any additional route — especially anything resembling medical data sync,
 * document upload, OCR, patient data or chats — fails this test.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { startServer, request } from './helpers.js';

const ALLOWED_PATHS = [
  '/auth/delete-account',
  '/auth/forgot-password',
  '/auth/google',
  '/auth/login',
  '/auth/logout',
  '/auth/me',
  '/auth/refresh',
  '/auth/register',
  '/auth/reset-password',
  '/health',
  '/models/:version/:filename',
  '/models/manifest',
];

const ALLOWED_METHOD_PATHS = [
  'GET /health',
  'POST /auth/register',
  'POST /auth/login',
  'POST /auth/google',
  'POST /auth/refresh',
  'POST /auth/logout',
  'POST /auth/forgot-password',
  'POST /auth/reset-password',
  'GET /auth/me',
  'POST /auth/delete-account',
  'GET /models/manifest',
  'GET /models/:version/:filename',
  'HEAD /models/:version/:filename',
];

/** Enumerate every route registered on the express app. */
function collectRoutes(app) {
  // Express 4 keeps the router at app._router; the app.router getter is a
  // deprecated accessor that throws, so we reach for the internal property.
  const router = app._router;
  assert.ok(router?.stack, 'express router must exist after app creation');
  const paths = new Set();
  const methodPaths = [];
  const routerLayers = [];
  for (const layer of router.stack) {
    if (layer.route) {
      paths.add(layer.route.path);
      for (const [method, enabled] of Object.entries(layer.route.methods)) {
        if (enabled) methodPaths.push(`${method.toUpperCase()} ${layer.route.path}`);
      }
    } else if (layer.name === 'router') {
      // Sub-routers would hide routes from this flat audit, so we forbid them.
      routerLayers.push(layer);
    }
  }
  return { paths: [...paths].sort(), methodPaths: methodPaths.sort(), routerLayers };
}

test('the route allowlist is exactly the authentication + model distribution set', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const { paths, methodPaths, routerLayers } = collectRoutes(server.app);

  assert.equal(
    routerLayers.length,
    0,
    'no express.Router sub-routers allowed: every route must be auditable on the app itself',
  );
  assert.deepEqual(paths, [...ALLOWED_PATHS].sort());
  assert.deepEqual(methodPaths, [...ALLOWED_METHOD_PATHS].sort());
});

test('forbidden medical-style endpoints return 404 (they do not exist)', async (t) => {
  const server = await startServer();
  t.after(() => server.close());

  const forbidden = [
    ['POST', '/medical-data'],
    ['POST', '/documents/upload'],
    ['POST', '/patient/sync'],
    ['GET', '/documents'],
    ['POST', '/ocr'],
    ['GET', '/chats'],
    ['POST', '/prompts'],
    ['GET', '/patients'],
    ['POST', '/sync'],
    ['GET', '/medical-data'],
    ['DELETE', '/documents/1'],
    ['POST', '/auth/../../medical-data'],
  ];

  for (const [method, pathname] of forbidden) {
    const res = await request(server.baseUrl, pathname, {
      method,
      // GET/HEAD must not carry a body (undici forbids it).
      ...(method === 'GET' || method === 'HEAD'
        ? {}
        : { body: { patient: 'x', diagnosis: 'y' }, headers: { 'content-type': 'application/json' } }),
    });
    assert.equal(res.status, 404, `${method} ${pathname} must be 404`);
    assert.deepEqual(res.json, { error: { code: 'not_found', message: 'Not found' } });
  }
});

test('GET /health is open and answers {status:"ok"}', async (t) => {
  const server = await startServer();
  t.after(() => server.close());
  const direct = await request(server.baseUrl, '/health');
  assert.equal(direct.status, 200);
  assert.deepEqual(direct.json, { status: 'ok' });
});
