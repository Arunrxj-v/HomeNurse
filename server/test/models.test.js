/**
 * Model distribution: manifest contract, streamed downloads, HTTP Range,
 * HEAD, 401/404 handling, and path-traversal defense (never reads outside
 * MODELS_DIR).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import {
  startServer,
  request,
  requestBuffer,
  registerUser,
  publishFixture,
  rawRequest,
  fixtureBytes,
  PUBLISH_SCRIPT,
} from './helpers.js';

const execFileAsync = promisify(execFile);
const sha256hex = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

async function publishedServer(t, options = {}) {
  const server = await startServer();
  t.after(() => server.close());
  const account = await registerUser(server, { username: 'modeluser' });
  const fixture = await publishFixture(server, options);
  return { server, account, fixture };
}

test('GET /models/manifest requires auth and returns the exact documented shape', async (t) => {
  const { server, account, fixture } = await publishedServer(t);
  const token = account.tokens.accessToken;

  const unauthenticated = await request(server.baseUrl, '/models/manifest');
  assert.equal(unauthenticated.status, 401);
  assert.equal(unauthenticated.json.error.code, 'unauthorized');

  const res = await request(server.baseUrl, '/models/manifest', { token });
  assert.equal(res.status, 200);

  const expected = {
    modelId: 'homenurse-gemma',
    version: '1.0.0',
    filename: 'homenurse-gemma.litertlm',
    sizeBytes: fixture.content.length,
    sha256: sha256hex(fixture.content),
    downloadUrl: 'https://api.test.example/models/1.0.0/homenurse-gemma.litertlm',
    runtime: 'litert-lm',
    minimumAndroidVersion: '26',
    minimumRamMb: 3072,
    displayName: 'HomeNurse AI (Gemma 3 1B)',
    lowEndDevice: false,
    licenseUrl: 'https://ai.google.dev/gemma/terms',
    noticeUrl: 'https://api.test.example/models/1.0.0/NOTICE',
  };
  assert.deepEqual(res.json, expected);
  assert.match(res.json.sha256, /^[0-9a-f]{64}$/, 'sha256 must be 64 lowercase hex chars');
  assert.equal(res.headers.get('content-type')?.includes('application/json'), true);
});

test('manifest 404 model_not_published when nothing has been published', async (t) => {
  const server = await startServer();
  t.after(() => server.close());
  const account = await registerUser(server, { username: 'freshuser' });

  const res = await request(server.baseUrl, '/models/manifest', { token: account.tokens.accessToken });
  assert.equal(res.status, 404);
  assert.equal(res.json.error.code, 'model_not_published');
});

test('publishing with custom flags is reflected in the manifest; NOTICE is written', async (t) => {
  const { server, account } = await publishedServer(t);

  const fixture = await publishFixture(server, {
    version: '2.0.0',
    modelId: 'homenurse-gemma-mini',
    displayName: 'HomeNurse AI Mini',
    fileName: 'mini.litertlm',
    content: fixtureBytes(1024),
    lowEnd: true,
    extraArgs: ['--min-ram-mb', '2048', '--min-android', '29', '--runtime', 'litert-lm'],
  });

  const res = await request(server.baseUrl, '/models/manifest', { token: account.tokens.accessToken });
  assert.equal(res.status, 200);
  assert.equal(res.json.version, '2.0.0');
  assert.equal(res.json.modelId, 'homenurse-gemma-mini');
  assert.equal(res.json.displayName, 'HomeNurse AI Mini');
  assert.equal(res.json.sizeBytes, 1024);
  assert.equal(res.json.minimumRamMb, 2048);
  assert.equal(res.json.minimumAndroidVersion, '29');
  assert.equal(res.json.lowEndDevice, true);
  assert.equal(res.json.downloadUrl, 'https://api.test.example/models/2.0.0/mini.litertlm');
  assert.equal(res.json.noticeUrl, 'https://api.test.example/models/2.0.0/NOTICE');
  assert.equal(res.json.sha256, sha256hex(fixture.content));

  // Gemma compliance: the NOTICE sits next to the artifact with the required text.
  const notice = await fs.readFile(path.join(server.modelsDir, '2.0.0', 'NOTICE'), 'utf8');
  assert.ok(
    notice.includes(
      'Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms',
    ),
    'NOTICE must contain the required Gemma sentence',
  );
  assert.ok(notice.includes('https://ai.google.dev/gemma/terms'));
  assert.ok(notice.includes('https://ai.google.dev/gemma/prohibited_use_policy'));
  assert.ok(!/endorses|endorsement of HomeNurse/i.test(notice), 'no Google endorsement claim');
});

test('model file: 200 full download with correct headers and exact bytes', async (t) => {
  const { server, account, fixture } = await publishedServer(t);
  const token = account.tokens.accessToken;
  const url = '/models/1.0.0/homenurse-gemma.litertlm';

  const unauthenticated = await requestBuffer(server.baseUrl, url);
  assert.equal(unauthenticated.status, 401);

  const res = await requestBuffer(server.baseUrl, url, { token });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/octet-stream');
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.equal(res.headers.get('content-length'), String(fixture.content.length));
  assert.equal(res.headers.get('etag'), `"${sha256hex(fixture.content)}"`);
  assert.ok(res.headers.get('last-modified'));
  assert.ok(res.buffer.equals(fixture.content), 'downloaded bytes must match the artifact exactly');
});

test('model file: HTTP Range support (206/416) with correct slices', async (t) => {
  const { server, account, fixture } = await publishedServer(t);
  const token = account.tokens.accessToken;
  const url = '/models/1.0.0/homenurse-gemma.litertlm';
  const size = fixture.content.length;

  const middle = await requestBuffer(server.baseUrl, url, { token, headers: { range: 'bytes=100-199' } });
  assert.equal(middle.status, 206);
  assert.equal(middle.headers.get('content-range'), `bytes 100-199/${size}`);
  assert.equal(middle.headers.get('content-length'), '100');
  assert.ok(middle.buffer.equals(fixture.content.subarray(100, 200)));

  const openEnded = await requestBuffer(server.baseUrl, url, { token, headers: { range: 'bytes=4000-' } });
  assert.equal(openEnded.status, 206);
  assert.equal(openEnded.headers.get('content-range'), `bytes 4000-${size - 1}/${size}`);
  assert.ok(openEnded.buffer.equals(fixture.content.subarray(4000)));

  const suffix = await requestBuffer(server.baseUrl, url, { token, headers: { range: 'bytes=-50' } });
  assert.equal(suffix.status, 206);
  assert.equal(suffix.headers.get('content-range'), `bytes ${size - 50}-${size - 1}/${size}`);
  assert.ok(suffix.buffer.equals(fixture.content.subarray(size - 50)));

  // Clamp an end beyond EOF.
  const clamped = await requestBuffer(server.baseUrl, url, { token, headers: { range: 'bytes=4000-999999' } });
  assert.equal(clamped.status, 206);
  assert.equal(clamped.headers.get('content-range'), `bytes 4000-${size - 1}/${size}`);

  // Unsatisfiable ranges -> 416 with Content-Range: bytes */size.
  for (const range of ['bytes=999999-1000000', 'bytes=4096-', 'bytes=-0', 'bytes=abc-def', 'items=0-10']) {
    const bad = await requestBuffer(server.baseUrl, url, { token, headers: { range } });
    assert.equal(bad.status, 416, `range ${range} must be 416`);
    assert.equal(bad.headers.get('content-range'), `bytes */${size}`);
    const parsed = JSON.parse(bad.buffer.toString('utf8'));
    assert.equal(parsed.error.code, 'range_not_satisfiable');
  }
});

test('model file: HEAD returns headers without a body', async (t) => {
  const { server, account, fixture } = await publishedServer(t);
  const token = account.tokens.accessToken;

  const res = await requestBuffer(server.baseUrl, '/models/1.0.0/homenurse-gemma.litertlm', {
    method: 'HEAD',
    token,
  });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/octet-stream');
  assert.equal(res.headers.get('content-length'), String(fixture.content.length));
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.equal(res.buffer.length, 0, 'HEAD must not send a body');
});

test('model file: unknown files and versions are 404', async (t) => {
  const { server, account } = await publishedServer(t);
  const token = account.tokens.accessToken;

  for (const pathname of ['/models/1.0.0/missing.bin', '/models/9.9.9/homenurse-gemma.litertlm']) {
    const res = await request(server.baseUrl, pathname, { token });
    assert.equal(res.status, 404, pathname);
    assert.equal(res.json.error.code, 'model_file_not_found');
  }
});

test('path traversal is rejected and never reads outside MODELS_DIR', async (t) => {
  const { server, account } = await publishedServer(t);
  const token = account.tokens.accessToken;

  const attempts = [
    // Encoded slashes inside a segment (decoded by express into the param).
    '/models/1.0.0/..%2f..%2fetc%2fpasswd',
    '/models/1.0.0/%2e%2e%2f%2e%2e%2fetc%2fpasswd',
    '/models/..%2f..%2fetc%2fpasswd/NOTICE',
    '/models/1.0.0/..%5c..%5cetc%5cpasswd',
    // Raw (non-normalized) traversal path, sent verbatim on the wire.
    { raw: '/models/1.0.0/../../etc/passwd' },
    { raw: '/models/1.0.0/../manifest.json' },
    { raw: '/models/1.0.0/../../../../etc/passwd' },
  ];

  for (const attempt of attempts) {
    let status;
    let text;
    if (attempt.raw) {
      const res = await rawRequest(server.baseUrl, attempt.raw, 'GET', {
        authorization: `Bearer ${token}`,
      });
      status = res.status;
      text = res.body.toString('utf8');
    } else {
      const res = await request(server.baseUrl, attempt.raw ?? attempt, { token });
      status = res.status;
      text = res.text;
    }
    assert.ok([400, 404].includes(status), `${attempt.raw ?? attempt} must be 400/404, got ${status}`);
    // The response must be an error envelope — never bytes from a file.
    const parsed = JSON.parse(text);
    assert.ok(
      ['invalid_input', 'not_found', 'model_file_not_found'].includes(parsed.error?.code),
      `unexpected error body for ${attempt.raw ?? attempt}: ${text}`,
    );
    assert.ok(!text.includes('root:'), 'must never return /etc/passwd contents');
  }

  // The legit file still works afterwards.
  const ok = await requestBuffer(server.baseUrl, '/models/1.0.0/homenurse-gemma.litertlm', { token });
  assert.equal(ok.status, 200);
});

test('NOTICE is downloadable at the manifest noticeUrl path', async (t) => {
  const { server, account } = await publishedServer(t);
  const token = account.tokens.accessToken;

  const res = await request(server.baseUrl, '/models/1.0.0/NOTICE', { token });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/octet-stream');
  assert.ok(res.text.includes('Gemma is provided under and subject to the Gemma Terms of Use'));
});

test('admin CLI: refuses duplicate versions without --force and verifies --sha256', async (t) => {
  const { server, fixture } = await publishedServer(t);

  const duplicate = await execFileAsync(
    process.execPath,
    [
      PUBLISH_SCRIPT,
      '--file', fixture.file,
      '--version', '1.0.0',
      '--model-id', 'homenurse-gemma',
      '--display-name', 'again',
      '--out', server.modelsDir,
    ],
    { cwd: path.dirname(PUBLISH_SCRIPT) },
  ).then(
    () => null,
    (err) => err,
  );
  assert.ok(duplicate, 'republishing the same version must fail');
  assert.match(duplicate.stderr, /already exists/);

  const badHash = await execFileAsync(
    process.execPath,
    [
      PUBLISH_SCRIPT,
      '--file', fixture.file,
      '--version', '3.0.0',
      '--model-id', 'homenurse-gemma',
      '--display-name', 'checked',
      '--out', server.modelsDir,
      '--sha256', 'f'.repeat(64),
    ],
    { cwd: path.dirname(PUBLISH_SCRIPT) },
  ).then(
    () => null,
    (err) => err,
  );
  assert.ok(badHash, 'a wrong --sha256 must fail the publish');
  assert.match(badHash.stderr, /sha256 mismatch/);
  await assert.rejects(fs.stat(path.join(server.modelsDir, '3.0.0')), /ENOENT/);

  // A correct --sha256 succeeds.
  const goodHash = await execFileAsync(
    process.execPath,
    [
      PUBLISH_SCRIPT,
      '--file', fixture.file,
      '--version', '4.0.0',
      '--model-id', 'homenurse-gemma',
      '--display-name', 'checked',
      '--out', server.modelsDir,
      '--sha256', sha256hex(fixture.content),
    ],
    { cwd: path.dirname(PUBLISH_SCRIPT) },
  );
  assert.match(goodHash.stdout, /published/);
});
