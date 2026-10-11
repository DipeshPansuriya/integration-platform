// T-161 (E6-F1-S2, lane D): the console build's licence check.
//
// Reads the installed PRODUCTION dependency tree - every non-dev package entry of package-lock.json,
// its licence from the lock or, when the lock carries none, from the installed package.json - and
// fails (exit 1, every offender named on stderr) when a licence is outside the allowed list below.
// The list is fixed by the backlog (T-161); widening it is a licence decision, never a code change
// made to get a build green. SPDX expressions are read as SPDX means them: "A OR B" passes when one
// choice is allowed, "A AND B" only when both are; a package that declares no licence is UNKNOWN and
// fails. With --out the result (name, version, licence, allowed) is written there - `npm run build`
// writes dist/licences.json after vite build, so the record ships with the build, pass or fail.
//
// Usage: node scripts/check-licences.mjs [--root <dir with package-lock.json>] [--out <file>]

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

export const ALLOWED = Object.freeze([
  'MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0', '0BSD', 'CC0-1.0', 'Unlicense', 'BlueOak-1.0.0'
]);

/** True when an SPDX licence expression is satisfiable from the allowed list. */
export function isAllowed(expression) {
  if (typeof expression !== 'string' || expression.trim() === '') return false;
  const text = expression.trim();
  const inner = text.startsWith('(') && text.endsWith(')') && balanced(text.slice(1, -1)) ? text.slice(1, -1) : text;
  const anyOf = splitTopLevel(inner, 'OR');
  if (anyOf.length > 1) return anyOf.some(isAllowed);
  const allOf = splitTopLevel(inner, 'AND');
  if (allOf.length > 1) return allOf.every(isAllowed);
  return ALLOWED.includes(inner.trim());
}

function balanced(text) {
  let depth = 0;
  for (const ch of text) {
    depth += ch === '(' ? 1 : ch === ')' ? -1 : 0;
    if (depth < 0) return false;
  }
  return depth === 0;
}

function splitTopLevel(text, operator) {
  const parts = [];
  let depth = 0;
  let start = 0;
  const tokens = text.split(/(\s+|\(|\))/);
  let offset = 0;
  for (const token of tokens) {
    if (token === '(') depth++;
    else if (token === ')') depth--;
    else if (depth === 0 && token === operator) {
      parts.push(text.slice(start, offset));
      start = offset + token.length;
    }
    offset += token.length;
  }
  parts.push(text.slice(start));
  return parts.map((part) => part.trim()).filter(Boolean);
}

/** The licence a package declares: the lock's field, else the installed package.json (legacy forms too). */
function licenceOf(root, key, entry) {
  if (typeof entry.license === 'string') return entry.license;
  const manifest = path.join(root, key, 'package.json');
  if (!fs.existsSync(manifest)) return 'UNKNOWN';
  const pkg = JSON.parse(fs.readFileSync(manifest, 'utf8'));
  if (typeof pkg.license === 'string') return pkg.license;
  if (pkg.license?.type) return pkg.license.type;
  if (Array.isArray(pkg.licenses) && pkg.licenses.length > 0) {
    return `(${pkg.licenses.map((l) => (typeof l === 'string' ? l : l.type)).join(' OR ')})`;
  }
  return 'UNKNOWN';
}

/** Every production package of the tree at `root`, sorted by name then version. */
export function checkTree(root) {
  const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
  const packages = [];
  for (const [key, entry] of Object.entries(lock.packages ?? {})) {
    if (key === '' || entry.dev || entry.link) continue; // the console itself; dev-only; workspace links
    const name = entry.name ?? key.slice(key.lastIndexOf('node_modules/') + 'node_modules/'.length);
    const licence = licenceOf(root, key, entry);
    packages.push({ name, version: entry.version ?? 'UNKNOWN', licence, allowed: isAllowed(licence) });
  }
  packages.sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
  return { allowedLicences: [...ALLOWED], packages, violations: packages.filter((p) => !p.allowed) };
}

function argument(args, name) {
  const at = args.indexOf(name);
  return at >= 0 ? args[at + 1] : undefined;
}

function main(args) {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const root = path.resolve(argument(args, '--root') ?? path.join(here, '..'));
  const out = argument(args, '--out');
  const result = checkTree(root);
  if (out) {
    const target = path.resolve(root, out);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, `${JSON.stringify(result, null, 2)}\n`);
  }
  if (result.violations.length > 0) {
    for (const v of result.violations) {
      console.error(`licence check FAILED: ${v.name}@${v.version} (${v.licence}) is not on the allowed list`);
    }
    console.error(`licence check: ${result.violations.length} of ${result.packages.length} production packages refused; allowed: ${ALLOWED.join(', ')}`);
    return 1;
  }
  console.log(`licence check: ${result.packages.length} production packages, all on the allowed list${out ? ` (written to ${out})` : ''}`);
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
