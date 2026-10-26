// node --test tests/e2e-full/unit/gap-watch.test.mjs - the fixme journeys must not outlive their gap.
// J3 waits on the T107 gap (the tracking read / detail screen carries no traceparent). The day the product shows it,
// this fails and says: turn J3 into a running test. J6 waits on a job trigger / clock advance in the boot (T120/T144).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const read = (rel) => readFileSync(path.join(ROOT, rel), 'utf8');
const walk = (dir) => (existsSync(dir)
  ? readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]))
  : []);

test('J3 gap still open: neither the tracking read (Java) nor the detail screen carries a traceparent', () => {
  const files = [
    ...walk(path.join(ROOT, 'platform-app/src/main/java/io/integrationplatform/app/message')),
    path.join(ROOT, 'ui/src/pages/MessageDetail.tsx'),
    path.join(ROOT, 'ui/src/pages/message-timeline.ts')
  ].filter((f) => existsSync(f));
  assert.ok(files.length > 3, 'the probe saw the tracking read and screen files');
  const hits = files.filter((f) => /traceparent/i.test(readFileSync(f, 'utf8')));
  assert.deepEqual(hits.map((f) => path.relative(ROOT, f)), [],
    'the traceparent is now on the tracking read/screen: un-fixme J3 in e2e-full-journeys.docker.spec.mjs and delete this probe');
});

test('both fixme journeys still name an item in their title', () => {
  const spec = read('ui/tests/e2e-full/e2e-full-journeys.docker.spec.mjs');
  assert.match(spec, /test\.fixme\('J3 \[T107 /);
  assert.match(spec, /test\.fixme\('J6 \[T120\/T144/);
});

test('no silent skip in the journeys spec: no test.skip, only named test.fixme', () => {
  const spec = read('ui/tests/e2e-full/e2e-full-journeys.docker.spec.mjs')
    .split('\n').filter((l) => !l.trim().startsWith('//')).join('\n');
  assert.equal(/\btest\.skip\s*\(/.test(spec), false);
});
