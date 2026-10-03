/**
 * Published model store: manifest file + safe path resolution inside MODELS_DIR.
 *
 * Only artifacts published by scripts/publish-model.js (an on-host admin CLI,
 * never an HTTP endpoint) are ever served.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { createJsonStore } from '../util/jsonStore.js';

const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;

export function createModelsStore({ config }) {
  const modelsDir = path.resolve(config.MODELS_DIR);
  const manifestPath = path.join(modelsDir, 'manifest.json');
  const manifestStore = createJsonStore(manifestPath, { defaultValue: {} });

  async function getManifest() {
    let raw;
    try {
      raw = await fs.readFile(manifestPath, 'utf8');
    } catch (err) {
      if (err && err.code === 'ENOENT') return null;
      throw err;
    }
    try {
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object') return null;
      if (typeof parsed.version !== 'string' || typeof parsed.filename !== 'string') return null;
      return parsed;
    } catch {
      return null;
    }
  }

  async function saveManifest(manifest) {
    await manifestStore.write(manifest);
  }

  /**
   * Resolve MODELS_DIR/<version>/<filename> with traversal protection:
   *  - both segments must match [A-Za-z0-9._-]+
   *  - neither segment may be "." or ".."
   *  - the resolved absolute path must stay inside MODELS_DIR and be exactly
   *    two levels deep (version/file)
   */
  function resolveModelFile(version, filename) {
    if (typeof version !== 'string' || typeof filename !== 'string') {
      return { ok: false, reason: 'invalid' };
    }
    if (!SAFE_SEGMENT.test(version) || !SAFE_SEGMENT.test(filename)) {
      return { ok: false, reason: 'invalid' };
    }
    if (version === '.' || version === '..' || filename === '.' || filename === '..') {
      return { ok: false, reason: 'invalid' };
    }
    const absolute = path.resolve(modelsDir, version, filename);
    const relative = path.relative(modelsDir, absolute);
    if (relative.startsWith('..') || path.isAbsolute(relative)) {
      return { ok: false, reason: 'outside' };
    }
    const parts = relative.split(path.sep);
    if (parts.length !== 2 || parts[0] !== version || parts[1] !== filename) {
      return { ok: false, reason: 'invalid' };
    }
    return { ok: true, absolute, version, filename };
  }

  return { modelsDir, manifestPath, getManifest, saveManifest, resolveModelFile };
}
