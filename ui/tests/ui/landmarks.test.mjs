// Row 310 B17 (sanjay-developer): one main landmark per screen (WCAG 1.3.1 / ARIA landmark rules).
// The shell (App.tsx) owns the single <main className="content">; every in-shell page renders its
// root as a plain element inside it. Login renders WITHOUT the shell and keeps its own single <main>.
// Static source check (no TypeScript loading):  node --test tests/ui/landmarks.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');

/** Counts JSX <main openings (`<main>`, `<main ...`, `<main` at a line end) - not <mainx or text. */
function mainCount(file) {
  return (readFileSync(file, 'utf8').match(/<main(?=[\s>])/g) ?? []).length;
}

function sourcesUnder(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourcesUnder(path);
    return /\.(tsx|ts|jsx|js)$/.test(entry.name) ? [path] : [];
  });
}

test('the shell owns exactly one <main> (App.tsx)', () => {
  assert.equal(mainCount(join(src, 'App.tsx')), 1);
});

test('Login (rendered without the shell) keeps exactly one <main>', () => {
  assert.equal(mainCount(join(src, 'pages', 'Login.tsx')), 1);
});

test('no in-shell page under src/pages or src/rules renders its own <main>', () => {
  const login = join(src, 'pages', 'Login.tsx');
  const offenders = [...sourcesUnder(join(src, 'pages')), ...sourcesUnder(join(src, 'rules'))]
    .filter((file) => file !== login && mainCount(file) > 0)
    .map((file) => `${relative(src, file)} (${mainCount(file)})`);
  assert.deepEqual(offenders, [], `nested <main> inside the shell's <main className="content">: ${offenders.join(', ')}`);
});
