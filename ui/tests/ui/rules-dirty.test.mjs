// Lane A round 2 (B27, audit A9 leftover): the rule-set editor knows when it holds unsaved edits -
// the browser asks before a reload or tab close, a visible note says so, and the test panel says it runs
// the saved version rather than the edits.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/rules-dirty.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'rules', 'RulesEditor.tsx'), 'utf8');

test('A9: dirty is set by a table or name edit and cleared by a successful save', () => {
  assert.match(page, /const \[dirty, setDirty\] = useState\(false\)/);
  assert.match(page, /adapter\.updateTable\(nodeId, table\);\s*\n\s*setSaved\(false\);\s*\n\s*setDirty\(true\)/);
  assert.match(page, /if \(result\.ok\) \{\s*\n?\s*setSaved\(true\);\s*\n?\s*setDirty\(false\)/);
});

test('A9: beforeunload guards unsaved edits; the notes are visible', () => {
  assert.match(page, /addEventListener\('beforeunload'/);
  assert.match(page, /data-unsaved="true"/);
  assert.match(page, /data-test-panel-note/);
  assert.match(page, /runs the last saved version/);
});
