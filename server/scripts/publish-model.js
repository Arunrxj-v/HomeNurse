#!/usr/bin/env node
/**
 * Admin CLI: verify and publish a model version. This is the ONLY way model
 * artifacts enter the server — there is intentionally no HTTP upload endpoint.
 *
 * Usage:
 *   node scripts/publish-model.js --file /path/model.litertlm --version 1.0.0 \
 *     --model-id homenurse-gemma --display-name "HomeNurse AI (Gemma 3 1B)" \
 *     [--min-ram-mb 3072] [--min-android 26] [--runtime litert-lm] [--low-end] \
 *     [--out MODELS_DIR] [--sha256 <expected-hex>] [--force]
 *
 * What it does:
 *   1. Streams the artifact into MODELS_DIR/<version>/<filename> while
 *      computing SHA-256 (or verifies a provided --sha256).
 *   2. Writes MODELS_DIR/<version>/NOTICE (copied from LICENSES/GEMMA_NOTICE.txt,
 *      required by the Gemma Terms of Use).
 *   3. Updates MODELS_DIR/manifest.json so this version is the one served.
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_ROOT = path.resolve(HERE, '..');
const NOTICE_SOURCE = path.join(SERVER_ROOT, 'LICENSES', 'GEMMA_NOTICE.txt');

const REQUIRED_NOTICE_SENTENCE =
  'Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms';

const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;
const HEX64 = /^[0-9a-f]{64}$/;

function usage() {
  return [
    'Usage: node scripts/publish-model.js --file <path> --version <v> --model-id <id> --display-name <name>',
    '         [--min-ram-mb 3072] [--min-android 26] [--runtime litert-lm] [--low-end]',
    '         [--out <MODELS_DIR>] [--sha256 <hex>] [--force]',
  ].join('\n');
}

function parseArgs(argv) {
  const args = {
    minRamMb: 3072,
    minAndroid: '26',
    runtime: 'litert-lm',
    lowEnd: false,
    force: false,
    out: process.env.MODELS_DIR || path.join(SERVER_ROOT, 'models-store'),
  };
  const wantsValue = new Set([
    '--file',
    '--version',
    '--model-id',
    '--display-name',
    '--min-ram-mb',
    '--min-android',
    '--runtime',
    '--out',
    '--sha256',
  ]);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--low-end') {
      args.lowEnd = true;
    } else if (arg === '--force') {
      args.force = true;
    } else if (arg === '-h' || arg === '--help') {
      args.help = true;
    } else if (wantsValue.has(arg)) {
      const value = argv[++i];
      if (value === undefined) throw new Error(`Missing value for ${arg}`);
      args[arg.replace(/^--/, '').replace(/-/g, '_')] = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function fail(message) {
  process.stderr.write(`publish-model: ${message}\n`);
  process.exit(1);
}

async function copyWithHash(source, destination) {
  const hash = crypto.createHash('sha256');
  const readStream = fs.createReadStream(source);
  readStream.on('data', (chunk) => hash.update(chunk));
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  await new Promise((resolve, reject) => {
    const writeStream = fs.createWriteStream(destination);
    readStream.on('error', reject);
    writeStream.on('error', reject);
    writeStream.on('finish', resolve);
    readStream.pipe(writeStream);
  });
  return hash.digest('hex');
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (err) {
    fail(`${err.message}\n\n${usage()}`);
    return;
  }
  if (args.help) {
    process.stdout.write(`${usage()}\n`);
    return;
  }

  for (const key of ['file', 'version', 'model_id', 'display_name']) {
    if (!args[key]) fail(`--${key.replace('_', '-')} is required\n\n${usage()}`);
  }

  const source = path.resolve(args.file);
  if (!SAFE_SEGMENT.test(args.version)) fail(`--version must match ${SAFE_SEGMENT}`);
  if (!SAFE_SEGMENT.test(args.model_id)) fail(`--model-id must match ${SAFE_SEGMENT}`);
  const filename = path.basename(source);
  if (!SAFE_SEGMENT.test(filename)) {
    fail(`artifact filename must match ${SAFE_SEGMENT} (got ${JSON.stringify(filename)}); rename the file`);
  }
  if (args.sha256 && !HEX64.test(args.sha256)) fail('--sha256 must be 64 lowercase hex characters');

  let sourceStat;
  try {
    sourceStat = await fsp.stat(source);
  } catch {
    fail(`file not found: ${source}`);
    return;
  }
  if (!sourceStat.isFile()) fail(`not a regular file: ${source}`);

  // Gemma compliance: the NOTICE must exist and contain the required sentence.
  let notice;
  try {
    notice = await fsp.readFile(NOTICE_SOURCE, 'utf8');
  } catch {
    fail(`missing ${NOTICE_SOURCE} (Gemma notice required for distribution)`);
    return;
  }
  if (!notice.includes(REQUIRED_NOTICE_SENTENCE)) {
    fail(`${NOTICE_SOURCE} does not contain the required Gemma notice sentence`);
    return;
  }

  const modelsDir = path.resolve(args.out);
  const versionDir = path.join(modelsDir, args.version);
  if (fs.existsSync(versionDir) && !args.force) {
    fail(`version directory already exists: ${versionDir} (use --force to replace it)`);
    return;
  }

  const minRamMb = Number(args.min_ram_mb ?? args.minRamMb);
  if (!Number.isInteger(minRamMb) || minRamMb <= 0) fail('--min-ram-mb must be a positive integer');

  // Stage into a temp dir so a failure never leaves a half-published version.
  const stagingDir = path.join(modelsDir, `.staging-${args.version}-${crypto.randomBytes(4).toString('hex')}`);
  await fsp.mkdir(stagingDir, { recursive: true });
  try {
    const stagedArtifact = path.join(stagingDir, filename);
    const sha256 = await copyWithHash(source, stagedArtifact);
    if (args.sha256 && sha256 !== args.sha256) {
      throw new Error(`sha256 mismatch: computed ${sha256}, expected ${args.sha256}`);
    }
    await fsp.copyFile(NOTICE_SOURCE, path.join(stagingDir, 'NOTICE'));

    const manifest = {
      modelId: args.model_id,
      version: args.version,
      filename,
      sizeBytes: sourceStat.size,
      sha256,
      runtime: args.runtime ?? 'litert-lm',
      minimumAndroidVersion: String(args.min_android ?? args.minAndroid),
      minimumRamMb: minRamMb,
      displayName: args.display_name,
      lowEndDevice: Boolean(args.lowEnd),
      licenseUrl: 'https://ai.google.dev/gemma/terms',
      updatedAt: new Date().toISOString(),
    };
    const manifestTmp = path.join(stagingDir, 'manifest.json.tmp');
    await fsp.writeFile(manifestTmp, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

    // Swap in the new version directory.
    await fsp.mkdir(modelsDir, { recursive: true });
    if (fs.existsSync(versionDir)) await fsp.rm(versionDir, { recursive: true, force: true });
    await fsp.rename(stagingDir, versionDir);

    // Update the manifest atomically (tmp + rename) in MODELS_DIR root.
    const manifestPath = path.join(modelsDir, 'manifest.json');
    const manifestRootTmp = `${manifestPath}.${crypto.randomBytes(4).toString('hex')}.tmp`;
    await fsp.rename(path.join(versionDir, 'manifest.json.tmp'), manifestRootTmp);
    await fsp.rename(manifestRootTmp, manifestPath);

    const summary = [
      'publish-model: published',
      `  modelId        ${manifest.modelId}`,
      `  version        ${manifest.version}`,
      `  file           ${filename} (${manifest.sizeBytes} bytes)`,
      `  sha256         ${manifest.sha256}`,
      `  runtime        ${manifest.runtime}`,
      `  minAndroid     ${manifest.minimumAndroidVersion}`,
      `  minRamMb       ${manifest.minimumRamMb}`,
      `  lowEndDevice   ${manifest.lowEndDevice}`,
      `  modelsDir      ${versionDir}`,
      `  notice         ${path.join(versionDir, 'NOTICE')}`,
      `  manifest       ${path.join(modelsDir, 'manifest.json')}`,
    ].join('\n');
    process.stdout.write(`${summary}\n`);
  } catch (err) {
    await fsp.rm(stagingDir, { recursive: true, force: true }).catch(() => {});
    fail(err.message);
  }
}

await main();
