/**
 * Minimal atomic JSON file store.
 *
 * - Reads always come from disk (files here are small and a separate process,
 *   e.g. scripts/publish-model.js, may write them too).
 * - Writes are atomic: data is written to a temp file in the same directory
 *   and then renamed over the target (rename is atomic on POSIX).
 * - All operations are serialized through a single promise chain so
 *   concurrent read-modify-write cycles never interleave inside one process.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

/**
 * @param {string} filePath absolute path of the JSON file
 * @param {{defaultValue?: any}} [options]
 */
export function createJsonStore(filePath, options = {}) {
  const defaultValue = options.defaultValue ?? {};
  let tail = Promise.resolve();
  let tmpCounter = 0;

  function enqueue(fn) {
    const run = tail.then(fn, fn);
    // Keep the chain alive even if this operation failed.
    tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async function load() {
    let raw;
    try {
      raw = await fs.readFile(filePath, 'utf8');
    } catch (err) {
      if (err && err.code === 'ENOENT') return structuredClone(defaultValue);
      throw err;
    }
    try {
      return JSON.parse(raw);
    } catch (err) {
      throw new Error(`Corrupt JSON store at ${filePath}: ${err.message}`);
    }
  }

  async function atomicWrite(data) {
    const dir = path.dirname(filePath);
    await fs.mkdir(dir, { recursive: true });
    const tmp = path.join(
      dir,
      `.${path.basename(filePath)}.${process.pid}.${tmpCounter++}.${crypto.randomBytes(4).toString('hex')}.tmp`,
    );
    const json = JSON.stringify(data, null, 2);
    await fs.writeFile(tmp, `${json}\n`, 'utf8');
    try {
      await fs.rename(tmp, filePath);
    } catch (err) {
      await fs.rm(tmp, { force: true });
      throw err;
    }
  }

  return {
    filePath,

    /** Read the current contents (always from disk, never returns a shared reference). */
    read() {
      return enqueue(load);
    },

    /** Atomically replace the contents. */
    write(data) {
      return enqueue(async () => {
        await atomicWrite(data);
        return data;
      });
    },

    /**
     * Serialized read-modify-write. `mutator` receives the current data and
     * returns the next data (or mutates it in place). If it throws, nothing
     * is written.
     */
    update(mutator) {
      return enqueue(async () => {
        const current = await load();
        const next = mutator(current);
        const value = next === undefined ? current : next;
        await atomicWrite(value);
        return value;
      });
    },
  };
}
