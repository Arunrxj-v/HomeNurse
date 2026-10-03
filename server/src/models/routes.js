/**
 * Model distribution endpoints (authenticated, read-only):
 *
 *   GET /models/manifest           -> published manifest JSON (exact contract shape)
 *   GET|HEAD /models/:version/:filename -> model bytes (Range-capable stream)
 *
 * PRIVACY: no uploads, no medical payloads, no query parameters — only
 * distribution of the on-device model artifact published by the admin CLI.
 */
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { HttpError, asyncHandler, sendJson } from '../util/http.js';

/** Parse a single-range `bytes=start-end` header. Returns null when unsatisfiable/invalid. */
export function parseRangeHeader(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/i.exec(String(header).trim());
  if (!match) return null;
  const [, rawStart, rawEnd] = match;
  if (rawStart === '' && rawEnd === '') return null;
  if (size === 0) return null;

  let start;
  let end;
  if (rawStart === '') {
    const suffix = Number(rawEnd);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return null;
    if (suffix >= size) {
      start = 0;
      end = size - 1;
    } else {
      start = size - suffix;
      end = size - 1;
    }
  } else {
    start = Number(rawStart);
    if (!Number.isSafeInteger(start)) return null;
    end = rawEnd === '' ? size - 1 : Number(rawEnd);
    if (!Number.isSafeInteger(end)) return null;
    if (start > end || start >= size) return null;
    if (end >= size) end = size - 1;
  }
  return { start, end };
}

/**
 * @param {import('express').Express} app
 * @param {{models: object, config: object, requireAuth: Function,
 *          rateLimiters: Record<string, Function>}} deps
 */
export function registerModelRoutes(app, { models, config, requireAuth, rateLimiters }) {
  // GET /models/manifest -> exact documented shape, 404 when nothing published.
  app.get(
    '/models/manifest',
    rateLimiters.modelsManifest,
    requireAuth,
    asyncHandler(async (req, res) => {
      const manifest = await models.getManifest();
      if (!manifest) {
        throw new HttpError(404, 'model_not_published', 'No model has been published yet.');
      }
      const version = String(manifest.version);
      const filename = String(manifest.filename);
      const versionPart = encodeURIComponent(version);
      const filenamePart = encodeURIComponent(filename);
      sendJson(res, 200, {
        modelId: String(manifest.modelId ?? ''),
        version,
        filename,
        sizeBytes: Number(manifest.sizeBytes),
        sha256: String(manifest.sha256),
        downloadUrl: `${config.PUBLIC_BASE_URL}/models/${versionPart}/${filenamePart}`,
        runtime: String(manifest.runtime ?? 'litert-lm'),
        minimumAndroidVersion: String(manifest.minimumAndroidVersion ?? '26'),
        minimumRamMb: Number(manifest.minimumRamMb ?? 3072),
        displayName: String(manifest.displayName ?? manifest.modelId ?? 'HomeNurse model'),
        lowEndDevice: Boolean(manifest.lowEndDevice),
        licenseUrl: String(manifest.licenseUrl ?? 'https://ai.google.dev/gemma/terms'),
        noticeUrl: `${config.PUBLIC_BASE_URL}/models/${versionPart}/NOTICE`,
      });
    }),
  );

  // GET /models/:version/:filename -> bytes, with Range/HEAD support.
  const serveFile = asyncHandler(async (req, res) => {
    const { version, filename } = req.params;
    const resolved = models.resolveModelFile(version, filename);
    if (!resolved.ok) {
      // Traversal attempts and malformed segments never touch the filesystem.
      throw new HttpError(400, 'invalid_input', 'Invalid model file path.');
    }

    let stat;
    try {
      stat = await fs.stat(resolved.absolute);
    } catch (err) {
      if (err && (err.code === 'ENOENT' || err.code === 'ENOTDIR')) {
        throw new HttpError(404, 'model_file_not_found', 'Model file not found.');
      }
      throw err;
    }
    if (!stat.isFile()) {
      throw new HttpError(404, 'model_file_not_found', 'Model file not found.');
    }

    const size = stat.size;
    const manifest = await models.getManifest();
    const manifestEtag =
      manifest && manifest.version === version && manifest.filename === filename && typeof manifest.sha256 === 'string'
        ? `"${manifest.sha256}"`
        : null;

    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('ETag', manifestEtag ?? `"${size}-${Math.floor(stat.mtimeMs)}"`);
    res.setHeader('Last-Modified', stat.mtime.toUTCString());

    let status = 200;
    let start = 0;
    let end = size - 1;

    if (req.headers.range !== undefined) {
      const range = parseRangeHeader(req.headers.range, size);
      if (!range) {
        res.setHeader('Content-Range', `bytes */${size}`);
        throw new HttpError(416, 'range_not_satisfiable', 'Requested range is not satisfiable.');
      }
      status = 206;
      start = range.start;
      end = range.end;
      res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
    }

    res.setHeader('Content-Length', String(end - start + 1));
    res.status(status);

    if (req.method === 'HEAD' || size === 0) {
      res.end();
      return;
    }

    await new Promise((resolve, reject) => {
      const stream = createReadStream(resolved.absolute, { start, end });
      stream.on('error', (err) => {
        stream.destroy();
        reject(err);
      });
      res.on('finish', resolve);
      res.on('close', () => {
        stream.destroy();
        resolve();
      });
      stream.pipe(res);
    });
  });

  app.get('/models/:version/:filename', rateLimiters.modelFile, requireAuth, serveFile);
  // Express routes HEAD through the GET handler; being explicit keeps the
  // allowlist enumeration deterministic.
  app.head('/models/:version/:filename', rateLimiters.modelFile, requireAuth, serveFile);
}
