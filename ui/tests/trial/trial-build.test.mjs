// Row 278 close-E7, T94 review fix (T-162) - the trial serves the console built from the CURRENT
// source, FAILING FIRST. No docker: ensureBuiltConsole of scripts/trial-up.mjs with an injected
// builder. A ui/dist left by an older build must never be served as-is (it can lack the newest
// screens, so the trial would fail for a reason that is not the trial user's).

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ensureBuiltConsole } from '../../scripts/trial-up.mjs';
import { UI } from '../../scripts/boot-backend.mjs';

async function runWith() {
  const builds = [];
  const lines = [];
  await ensureBuiltConsole({ build: async (options) => { builds.push(options); }, print: (line) => lines.push(line) });
  return { builds, lines };
}

test('builds the console even when ui/dist/index.html already exists', async (t) => {
  const index = path.join(UI, 'dist', 'index.html');
  const existed = fs.existsSync(index);
  if (!existed) {
    fs.mkdirSync(path.dirname(index), { recursive: true });
    fs.writeFileSync(index, '<!-- stale build left by an older run -->');
    t.after(() => fs.rmSync(index, { force: true }));
  }
  const { builds, lines } = await runWith();
  assert.equal(builds.length, 1, 'a stale ui/dist must be rebuilt from the current source');
  assert.equal(builds[0].root, UI, 'the build runs on ui/');
  assert.equal(lines.length, 1, 'one print line says the console is being built');
  assert.match(lines[0], /^trial-up: building the console/);
});
