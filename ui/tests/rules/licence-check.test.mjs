// T-161 (E6-F1-S2, lane D): the console build's licence check - written FAILING FIRST.
//
//   Scenario: The licence is recorded and checked
//     Given the console build
//     When the licence check runs
//     Then @gorules/jdm-editor is reported as MIT and the result is stored with the build
//   @negative @security
//   Scenario: A non-allowed licence fails the build
//     Given a dependency whose licence is not on the allowed list (for example AGPL or BSL in the console)
//     When the licence check runs
//     Then the build fails and names the dependency
//
// The checker reads the installed PRODUCTION dependency tree (package-lock.json, falling back to the
// installed package.json) and fails on any licence outside the allowed list. The negative cases plant
// fixture trees (tests/rules/fixtures/licence-*) so the guard is proven to fail (R02).

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..', '..');
const CHECKER = path.join(UI, 'scripts', 'check-licences.mjs');
const FIXTURES = path.join(HERE, 'fixtures');

function runCheck(root, out) {
  const args = [CHECKER, '--root', root];
  if (out) args.push('--out', out);
  return spawnSync(process.execPath, args, { encoding: 'utf8' });
}

function tempOut() {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'licence-check-')), 'licences.json');
}

test('the licence is recorded and checked: @gorules/jdm-editor is reported as MIT and the result is stored', () => {
  const out = tempOut();
  const run = runCheck(UI, out);
  assert.ok(fs.existsSync(out), `the checker stores its result (exit ${run.status}, stderr: ${run.stderr})`);
  const report = JSON.parse(fs.readFileSync(out, 'utf8'));
  const editor = report.packages.find((entry) => entry.name === '@gorules/jdm-editor');
  assert.deepEqual(
    editor && { name: editor.name, version: editor.version, licence: editor.licence, allowed: editor.allowed },
    { name: '@gorules/jdm-editor', version: '1.52.0', licence: 'MIT', allowed: true }
  );
  assert.ok(report.packages.every((entry) => entry.name && entry.version && 'licence' in entry));
});

test('the result is stored WITH the build: npm run build runs the checker into dist/licences.json', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(UI, 'package.json'), 'utf8'));
  assert.match(pkg.scripts['licence:check'] ?? '', /node scripts\/check-licences\.mjs --out dist\/licences\.json/);
  // vite build empties dist/, so the check must come after it for the report to survive.
  assert.match(pkg.scripts.build ?? '', /vite build && npm run licence:check$/);
});

test('@negative @security an AGPL production dependency fails the check and is named', () => {
  const out = tempOut();
  const run = runCheck(path.join(FIXTURES, 'licence-agpl'), out);
  assert.notEqual(run.status, 0, 'a non-allowed licence must fail the build');
  assert.match(run.stderr, /planted-agpl@2\.3\.4/);
  assert.match(run.stderr, /AGPL-3\.0/);
  assert.doesNotMatch(run.stderr, /allowed-mit/, 'an allowed package is not named as a violation');
  assert.doesNotMatch(run.stderr, /dev-only-gpl/, 'only the production tree is checked');
  const report = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.deepEqual(report.violations.map((v) => v.name), ['planted-agpl'], 'the stored result names it too');
});

test('@negative @security a BSL (BUSL-1.1) production dependency fails the check and is named', () => {
  const run = runCheck(path.join(FIXTURES, 'licence-bsl'));
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /@scope\/planted-bsl@0\.9\.0/);
  assert.match(run.stderr, /BUSL-1\.1/);
});

test('@negative @security an undeclared licence and an AND with a non-allowed licence both fail', () => {
  const run = runCheck(path.join(FIXTURES, 'licence-unknown'));
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /undeclared@0\.1\.1 \(UNKNOWN\)/);
  assert.match(run.stderr, /both-required@1\.0\.11 \(\(MIT AND Zlib\)\)/);
});

test('an allowed tree passes: nested packages and an OR expression with an allowed choice', () => {
  const out = tempOut();
  const run = runCheck(path.join(FIXTURES, 'licence-ok'), out);
  assert.equal(run.status, 0, run.stderr);
  const report = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.deepEqual(report.packages.map((p) => p.name).sort(), ['allowed-mit', 'dual-licensed', 'nested-isc']);
  assert.deepEqual(report.violations, []);
});

// The console's REAL production tree against the unchanged allowed list. Four transitive packages of
// jdm-editor 1.52.0 (via exceljs) are outside it - pako (MIT AND Zlib), buffers (no licence declared),
// chainsaw and traverse ("MIT/X11", not an SPDX id). Widening the list is a licence decision, not this
// lane's (Open for arjun-fullstack, T49); until it is made the build itself fails on them, and this
// case reports as TODO with the names instead of hiding them.
test('the console production tree passes the allowed list', { todo: 'licence decision open for arjun-fullstack (T49)' }, () => {
  const run = runCheck(UI);
  assert.equal(run.status, 0, run.stderr);
});
