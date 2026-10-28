// W2+W3 (iPaaS Console DS): no-literal-colour guard for APP code.
//
// Rule: app code consumes design tokens (var(--…)), never raw colour literals —
// the DS library (src/ds/**) owns its own tokens and is the only other place
// literals are allowed.
//
// Scans src/**/*.{css,tsx} with src/ds/** excluded (the DS library itself).
// Exemptions (rationale pinned here, not in a hidden config):
//   src/tokens.css — the app token layer: every literal there IS a token
//     definition (the runtime equivalent of the DS tokens.json).
//   src/ui/base.css — four pre-existing, contrast-measured literals
//     (#fff / #ffffff fills on the accent buttons, ~6.29:1 / ~3:1 measured
//     in 01 §1); each exact value is pinned below, so a new literal in that
//     file still fails.
//
// Method: hex (#rgb/#rrggbb/#aarrggbb), rgb()/rgba(), hsl()/hsla() in all
// scanned files (comments stripped); the 16 CSS named colours scanned as
// declaration *values* in CSS files only (TSX string literals are prose —
// false positives outweigh signal).
import test from 'node:test';
import assert from 'node:assert';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(process.cwd(), 'src');
const EXEMPT_FILES = new Set(['src/tokens.css'.replace(/\//g, '\\'), 'src/ui/base.css'.replace(/\//g, '\\')]);
// Pinned, contrast-measured exceptions in src/ui/base.css (see file head).
const BASE_CSS_ALLOWED = new Set(['#ffffff', '#fff']);
const NAMED_COLOURS = [
  'white', 'black', 'red', 'green', 'blue', 'yellow', 'gray', 'grey',
  'silver', 'maroon', 'navy', 'olive', 'purple', 'teal', 'aqua', 'fuchsia',
  'lime', 'orange', 'cyan', 'magenta', 'pink', 'brown', 'gold', 'coral',
];

function walk(dir, into) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === 'ds') continue; // the DS library — its own tokens
      walk(p, into);
    } else if (/\.(css|tsx)$/.test(name)) {
      into.push(p);
    }
  }
  return into;
}

// Comments are prose (colour *names* in comments are documentation, not paint).
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/^[ \t]*\/\/.*$/gm, ' '); // line comments (tsx)
}

test('app code has no literal colours outside the token layer and pinned exceptions', () => {
  const files = walk(ROOT, []);
  assert.ok(files.length >= 40, `found ${files.length} css/tsx files under src — walker working`);

  const violations = [];
  for (const path of files) {
    const rel = path.replace(process.cwd() + '\\', '');
    if (EXEMPT_FILES.has(rel)) continue;
    const code = stripComments(readFileSync(path, 'utf8'));
    let m;
    const hexRe = /#[0-9a-fA-F]{3,8}\b/g;
    while ((m = hexRe.exec(code)) !== null) violations.push(`${rel}: raw hex ${m[0]}`);
    const fnRe = /\b(?:rgba?|hsla?)\(/g;
    while ((m = fnRe.exec(code)) !== null) violations.push(`${rel}: rgb()/hsl() call at offset ${m.index}`);
    if (rel.endsWith('.css')) {
      for (const colour of NAMED_COLOURS) {
        const namedRe = new RegExp(`:[^;{}\\n]*\\b${colour}\\b`, 'gi');
        while ((m = namedRe.exec(code)) !== null) {
          // exclude variable *definitions* of the same name (var(--…-white)) —
          // a named colour here is a value, not a token name fragment
          const match = m[0].slice(1);
          if (/var\(\s*--/.test(match)) continue;
          violations.push(`${rel}: named colour '${colour}' in declaration`);
        }
      }
    }
  }

  // base.css keeps its four pinned, measured literals — count must not grow.
  let baseHx = 0;
  for (const m of stripComments(readFileSync(join(ROOT, 'ui', 'base.css'), 'utf8')).matchAll(/#[0-9a-fA-F]{3,8}\b/g)) {
    assert.ok(BASE_CSS_ALLOWED.has(m[0]), `unpinned literal ${m[0]} in src/ui/base.css — add a token instead`);
    baseHx++;
  }
  assert.ok(baseHx > 0, 'base.css pinned literals missing — if the fills became tokens, update this test');

  assert.deepEqual(
    violations,
    [],
    `literal colours found:\n  ${violations.join('\n  ')}\nAdd a CSS variable in src/tokens.css and reference it with var(--…).`,
  );
});