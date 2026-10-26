// The harness boots a jar it may have built hours ago. A jar older than any source that goes into it must be
// rebuilt (run 9: a 00:19 jar booted after later merges answered 202 where main answers 422). Pure: temp files only.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { jarIsStale, newestSourceMtime } from '../../../scripts/boot-backend.mjs';

function repoWith(files) {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'jar-stale-'));
  for (const [name, seconds] of Object.entries(files)) {
    const file = path.join(repo, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, 'x');
    fs.utimesSync(file, seconds, seconds);
  }
  return repo;
}

test('newestSourceMtime: pom.xml files and src/main count; src/test, target and ui do not', () => {
  const repo = repoWith({
    'pom.xml': 100, 'platform-a/pom.xml': 200, 'platform-a/src/main/java/A.java': 300,
    'platform-a/src/test/java/ATest.java': 9000, 'platform-a/target/classes/A.class': 9000, 'ui/x.mjs': 9000
  });
  assert.equal(newestSourceMtime(repo), 300_000);
});

test('jarIsStale: a source newer than the jar -> stale; a jar newer than every source -> fresh', () => {
  const repo = repoWith({ 'pom.xml': 100, 'platform-a/src/main/java/A.java': 300, 'platform-a/target/a.jar': 250 });
  const jar = path.join(repo, 'platform-a/target/a.jar');
  assert.equal(jarIsStale(jar, repo), true);
  fs.utimesSync(jar, 400, 400);
  assert.equal(jarIsStale(jar, repo), false);
  const pom = path.join(repo, 'platform-a/pom.xml');
  fs.writeFileSync(pom, 'x');
  fs.utimesSync(pom, 500, 500);
  assert.equal(jarIsStale(jar, repo), true, 'a module pom newer than the jar is stale too');
});

test('newestSourceMtime: an empty repo is 0 (nothing to be newer than)', () => {
  assert.equal(newestSourceMtime(fs.mkdtempSync(path.join(os.tmpdir(), 'jar-stale-'))), 0);
});
