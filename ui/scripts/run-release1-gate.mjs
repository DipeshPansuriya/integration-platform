// T174 (lane t174-sc): boots the base-suite backend ONCE inside its own docker-suite slot, runs the platform-e2e
// Release 1 gate (Release1ScenariosContainerTest) against it with -De2e.baseUrl, then tears the backend down.
// Secrets stay in this process's memory; nothing is written but the Maven log under the path given as argv[2].
// Same exception settings and docker-suite slot variable as the base suite (see tests/base-suite/lib/settings.mjs).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import bootBackend, { REPO } from './boot-backend.mjs';
import { baseSuiteBootOptions } from '../tests/base-suite/lib/boot-options.mjs';
import { exceptionSettings } from '../tests/base-suite/lib/settings.mjs';

const logFile = process.argv[2];
if (!logFile) throw new Error('usage: run-release1-gate.mjs <log-file>');

const backend = await bootBackend(baseSuiteBootOptions(exceptionSettings(process.env), { lockHolder: 'aditya t174-sc' }));
try {
  const health = await fetch(`${backend.baseUrl}/actuator/health`).then((r) => r.status).catch((e) => e.message);
  const intake = await fetch(`${backend.baseUrl}/api/v1/interfaces/REF-ORDER/messages`, { method: 'POST' }).then((r) => r.status).catch((e) => e.message);
  fs.appendFileSync(logFile, `backend ${backend.baseUrl}: /actuator/health -> ${health}; unauthenticated POST intake REF-ORDER -> ${intake}\n`);
  const mvnw = process.platform === 'win32' ? `"${path.join(REPO, 'mvnw.cmd')}"` : './mvnw';
  const code = await new Promise((resolve) => {
    const child = spawn(mvnw, ['-o', '-Pcontainer', '-pl', 'platform-e2e', 'test', '-Dtest=Release1ScenariosContainerTest', `-De2e.baseUrl=${backend.baseUrl}`],
      { cwd: REPO, shell: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const out = fs.createWriteStream(logFile, { flags: 'a' });
    child.stdout.pipe(out, { end: false });
    child.stderr.pipe(out, { end: false });
    child.on('close', (c) => { out.end(); resolve(c); });
  });
  console.log(`mvn exit ${code}`);
} finally {
  await backend.teardown();
}
