// L29 static proof, no Docker: the console paths the suites assume exist, and EVERY relative import in e2e-ui resolves.
// Written after review B1 (openapi-contract.docker.test.mjs kept a path to a file that stayed in ui/).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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

// Every relative specifier, whatever the quote style (single, double, backtick) and form (static, side-effect, re-exported, dynamic, require).
function relativeSpecifiers(text) {
  const specifier = /(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s*|\brequire\s*\(\s*)(['"`])(\.{1,2}\/[^'"`\r\n]+)\1/g;
  return [...text.matchAll(specifier)].map((match) => match[2]);
}

test('the import scan sees single, double and backtick quotes in every import form', () => {
  const sample = [
    "import a from './single.mjs';", 'import b from "../double.mjs";', 'import "./side-effect.mjs";',
    'export { c } from "./re-exported.mjs";', 'const d = await import(`./dynamic.mjs`);', "const e = require('./cjs.cjs');",
    "import f from 'node:fs';", "import g from 'pkg/sub';"
  ].join('\n');
  assert.deepEqual(relativeSpecifiers(sample), ['./single.mjs', '../double.mjs', './side-effect.mjs', './re-exported.mjs', './dynamic.mjs', './cjs.cjs']);
});

test('every relative import / dynamic import in e2e-ui points at a file that exists', () => {
  const broken = [];
  const self = fileURLToPath(import.meta.url); // its own scanner sample holds deliberately unresolvable specifiers
  for (const file of sources(E2E_UI).filter((candidate) => candidate !== self)) {
    for (const spec of relativeSpecifiers(fs.readFileSync(file, 'utf8'))) {
      if (!fs.existsSync(path.resolve(path.dirname(file), spec))) broken.push(`${path.relative(E2E_UI, file)} -> ${spec}`);
    }
  }
  assert.deepEqual(broken, []);
});

test('every openapi-contract.docker.test.mjs imports lib-console-paths.mjs, so nobody re-inlines a console path constant', () => {
  const contracts = sources(E2E_UI).filter((file) => path.basename(file) === 'openapi-contract.docker.test.mjs');
  assert.ok(contracts.length > 0, 'control: at least one openapi-contract.docker.test.mjs exists');
  for (const file of contracts) {
    const imported = relativeSpecifiers(fs.readFileSync(file, 'utf8')).map((spec) => path.resolve(path.dirname(file), spec));
    assert.ok(imported.includes(path.join(E2E_UI, 'tests', 'lib-console-paths.mjs')), `${path.relative(E2E_UI, file)} imports lib-console-paths.mjs`);
  }
});
