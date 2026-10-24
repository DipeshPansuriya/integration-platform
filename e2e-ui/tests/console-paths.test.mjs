// L29 static proof, no Docker: the console paths the suites assume exist, and EVERY relative import in e2e-ui resolves.
// Written after review B1 (openapi-contract.docker.test.mjs kept a path to a file that stayed in ui/).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { E2E_UI, CONSOLE, GENERATED_TS, COMMITTED_DOC, TYPESCRIBE, TSCONFIG } from './lib-console-paths.mjs';

test('the console project paths the OpenAPI contract test uses exist in ui/', () => {
  for (const file of [GENERATED_TS, COMMITTED_DOC, TYPESCRIBE, TSCONFIG]) {
    assert.ok(fs.existsSync(file), `${file} exists`);
    assert.ok(file.startsWith(CONSOLE + path.sep), `${file} is under ui/`);
  }
});

function sources(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    if (entry.name === 'node_modules' || entry.name === 'test-results') return [];
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? sources(full) : full.endsWith('.mjs') ? [full] : [];
  });
}

test('every relative import / dynamic import in e2e-ui points at a file that exists', () => {
  const specifier = /(?:from\s+|import\s*\(\s*|import\s+)'(\.{1,2}\/[^']+)'/g;
  const broken = [];
  for (const file of sources(E2E_UI)) {
    for (const [, spec] of fs.readFileSync(file, 'utf8').matchAll(specifier)) {
      if (!fs.existsSync(path.resolve(path.dirname(file), spec))) broken.push(`${path.relative(E2E_UI, file)} -> ${spec}`);
    }
  }
  assert.deepEqual(broken, []);
});
