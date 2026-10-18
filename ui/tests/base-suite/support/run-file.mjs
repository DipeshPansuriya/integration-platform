// T130 (row 323, F21 BS-B): what one base-suite run hands from the seed project to the scenarios and from
// BS-07 to BS-12 - ids and tracking ids ONLY (test-results/, gitignored, removed at the next seed). A source
// credential's secret never lands here (R07): a scenario rotates the credential and keeps the new secret
// in memory. A key that looks like a secret is refused outright.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RUN_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'test-results', '.bs-run.json');
const SECRET_KEY = /secret|password|token|cookie|xsrf/i;

function refuseSecrets(value, where = 'run') {
  if (value && typeof value === 'object') {
    for (const [key, inner] of Object.entries(value)) {
      if (SECRET_KEY.test(key)) throw new Error(`run-file: ${where}.${key} looks like a secret - never written`);
      refuseSecrets(inner, `${where}.${key}`);
    }
  }
}

export function startRun(seed) {
  refuseSecrets(seed);
  fs.mkdirSync(path.dirname(RUN_FILE), { recursive: true });
  fs.writeFileSync(RUN_FILE, JSON.stringify({ seed, tracking: { A: [], B: [] } }, null, 2));
}

export function readRun() {
  if (!fs.existsSync(RUN_FILE)) throw new Error('run-file: no seed - the bs-seed project must run first');
  return JSON.parse(fs.readFileSync(RUN_FILE, 'utf8'));
}

/** Records the tracking ids a tenant's 202 answers carried (BS-12's provenance check reads them). */
export function addTracking(tenantKey, ids) {
  const run = readRun();
  run.tracking[tenantKey] = [...run.tracking[tenantKey], ...ids];
  refuseSecrets(run);
  fs.writeFileSync(RUN_FILE, JSON.stringify(run, null, 2));
}
