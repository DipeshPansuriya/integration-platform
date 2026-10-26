// L29 static proof, no Docker: the console paths the suites assume exist, and EVERY relative import in e2e-ui resolves.
// Written after review B1 (openapi-contract.docker.test.mjs kept a path to a file that stayed in ui/).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as paths from './lib-console-paths.mjs';
import { E2E_UI, CONSOLE, GENERATED_TS, COMMITTED_DOC, TYPESCRIBE, TSCONFIG } from './lib-console-paths.mjs';

const HELPER = path.join(E2E_UI, 'tests', 'lib-console-paths.mjs');

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

// Source with comments removed (an import in a comment is not an import).
function code(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

// Every relative specifier, whatever the quote style (single, double, backtick) and form. Static forms must START a statement (line start) and
// dynamic import()/require() must not sit right after a quote, so an import inside a comment or a string is not counted.
function relativeSpecifiers(text) {
  const source = code(text);
  const specifier = /(?:^[ \t]*(?:import|export)\b[^;'"`]*?\bfrom\s*|^[ \t]*import\s*|(?<![\w.'"`])import\s*\(\s*|(?<![\w.'"`])require\s*\(\s*)(['"`])(\.{1,2}\/[^'"`\r\n]+)\1/gm;
  return [...source.matchAll(specifier)].map((match) => match[2]);
}

test('the import scan sees single, double and backtick quotes in every import form, and skips comments and strings', () => {
  const sample = [
    "import a from './single.mjs';", 'import b from "../double.mjs";', 'import "./side-effect.mjs";',
    'export { c } from "./re-exported.mjs";', 'const d = await import(`./dynamic.mjs`);', "const e = require('./cjs.cjs');",
    "import f from 'node:fs';", "import g from 'pkg/sub';",
    'import {', '  h,', "} from './multi-line.mjs';",
    "// import x from './line-comment.mjs';", "/* import y from './block-comment.mjs'; */", 'const t = "import(\'./in-string.mjs\')";'
  ].join('\n');
  assert.deepEqual(relativeSpecifiers(sample), ['./single.mjs', '../double.mjs', './side-effect.mjs', './re-exported.mjs', './dynamic.mjs', './cjs.cjs', './multi-line.mjs']);
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

// What is wrong with a contract test's use of the shared console paths: not imported, imported but not used, or a name re-declared inline.
function contractProblems(text, file) {
  const source = code(text);
  const problems = [];
  const imported = relativeSpecifiers(text).map((spec) => path.resolve(path.dirname(file), spec));
  if (!imported.includes(HELPER)) problems.push('does not import lib-console-paths.mjs');
  const clause = source.match(/import\s*\{([^}]*)\}\s*from\s*(['"`])\.\/lib-console-paths\.mjs\2;?/);
  const names = clause ? clause[1].split(',').map((part) => part.trim().split(/\s+as\s+/).pop()).filter(Boolean) : [];
  const body = clause ? source.replace(clause[0], '') : source;
  if (clause && names.length === 0) problems.push('imports nothing from lib-console-paths.mjs');
  for (const name of names) if (!new RegExp(String.raw`\b${name}\b`).test(body)) problems.push(`imports ${name} but never uses it`);
  for (const name of Object.keys(paths)) {
    if (new RegExp(String.raw`\b(?:const|let|var|function|class)\s+${name}\b|\b(?:const|let|var)\s*\{[^}]*\b${name}\b[^}]*\}\s*=`).test(body)) problems.push(`re-declares ${name} inline`);
  }
  return problems;
}

test('the contract-test check catches a missing import, an unused import and an inline constant beside a kept import', () => {
  const file = path.join(E2E_UI, 'tests', 'x.docker.test.mjs');
  const good = "import { CONSOLE, COMMITTED_DOC } from './lib-console-paths.mjs';\nfs.readFileSync(COMMITTED_DOC); run({ cwd: CONSOLE });";
  assert.deepEqual(contractProblems(good, file), []);
  assert.deepEqual(contractProblems("const CONSOLE = '/wrong';\nrun({ cwd: CONSOLE });", file), ['does not import lib-console-paths.mjs', 're-declares CONSOLE inline']);
  assert.deepEqual(contractProblems("import { CONSOLE } from './lib-console-paths.mjs';\nconst CONSOLE2 = 1;\nconst GENERATED_TS = '/wrong';\nfs.read(GENERATED_TS);", file), ['imports CONSOLE but never uses it', 're-declares GENERATED_TS inline']);
  assert.deepEqual(contractProblems("// import { CONSOLE } from './lib-console-paths.mjs';\nconst CONSOLE = '/wrong';", file), ['does not import lib-console-paths.mjs', 're-declares CONSOLE inline']);
});

test('every openapi-contract.docker.test.mjs imports lib-console-paths.mjs and uses it, so nobody re-inlines a console path constant', () => {
  const contracts = sources(E2E_UI).filter((file) => path.basename(file) === 'openapi-contract.docker.test.mjs');
  assert.ok(contracts.length > 0, 'control: at least one openapi-contract.docker.test.mjs exists');
  for (const file of contracts) assert.deepEqual(contractProblems(fs.readFileSync(file, 'utf8'), file), [], path.relative(E2E_UI, file));
});
