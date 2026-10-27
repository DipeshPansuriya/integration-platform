// Row 484 (security finding H-1, PR 34; Dipesh chose option A): the destination allow-list is ONE global list owned by the
// operator (ADR-010, T97). Until the operator login exists (ADR-009) the console has no screen that edits it and no
// client call to an allow-list endpoint: a tenant admin or configurator signed in here must find nothing to click.
// Run: node --test tests/ui/no-allow-list-editor.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (path) => readFileSync(join(src, path), 'utf8');

/** Every .ts / .tsx / .css source file under src, as paths relative to src. */
function sourceFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.(tsx?|css)$/.test(entry.name) ? [relative(src, full).replaceAll('\\', '/')] : [];
  });
}

test('no console source calls an allow-list endpoint (allowed-hosts / allow-list)', () => {
  const hits = sourceFiles(src).filter((file) =>
    /admin\/settings\/allowed-hosts|\/allow-?list/i.test(read(file)),
  );
  assert.deepEqual(hits, []);
});

test('the Settings page, its client and its stylesheet are gone', () => {
  for (const file of ['pages/Settings.tsx', 'pages/settings.css', 'api/settings.ts']) {
    assert.equal(existsSync(join(src, file)), false, `${file} must not exist`);
  }
});

test('the shell wires no settings route, no settings page and no Settings menu group', () => {
  const app = read('App.tsx');
  assert.doesNotMatch(app, /pages\/Settings/);
  assert.doesNotMatch(app, /page:\s*'settings'/);
  assert.doesNotMatch(app, /nav:\s*'settings'/);
  assert.doesNotMatch(app, /startsWith\('settings'\)/);
  assert.doesNotMatch(app, /title:\s*'Settings'/);
  assert.doesNotMatch(app, /'Allowed hosts'/);
});
