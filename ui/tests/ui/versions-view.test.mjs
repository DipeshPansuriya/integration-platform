// Lane A (B27, audit A4/A7): the Versions lifecycle panel words every failure, shows a busy state while
// a request is in flight, and announces status changes politely.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/versions-view.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { actionFailure, statusAnnouncement, PENDING_LABEL } = await import('../../src/pages/versions-view.ts');
const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'pages', 'Versions.tsx'), 'utf8');

test('A4: every lifecycle action has a worded failure naming the action and what to do', () => {
  const expect = {
    'save-draft': /save the draft/,
    'submit-for-approval': /submit the version for approval/,
    approve: /approve the version/,
    publish: /publish the version/,
    'new-draft': /start a new draft/,
    open: /open the version/
  };
  for (const [action, pattern] of Object.entries(expect)) {
    for (const status of [0, 403, 500]) {
      const text = actionFailure(action, status);
      assert.match(text, pattern, `${action}/${status}`);
      assert.doesNotMatch(text, /\b\d{3}\b/, 'no bare HTTP code');
    }
  }
});

test('A7: status changes are announced in plain words; an unknown status announces nothing', () => {
  assert.match(statusAnnouncement('SUBMITTED'), /Submitted/);
  assert.match(statusAnnouncement('APPROVED'), /Approved/);
  assert.match(statusAnnouncement('PUBLISHED'), /Published/);
  assert.match(statusAnnouncement('DRAFT'), /Draft/);
  assert.equal(statusAnnouncement('WEIRD'), '');
});

test('A7: each action has a pending label for its busy button', () => {
  for (const action of ['save-draft', 'submit-for-approval', 'approve', 'publish', 'new-draft']) {
    assert.ok(PENDING_LABEL[action], action);
  }
});

test('wiring: lifecycle buttons are disabled while pending, a polite live region exists, failures are role=alert, load error has Retry', () => {
  assert.match(page, /aria-disabled=\{pending !== null \? 'true' : undefined\}/); // row 372 M2
  assert.match(page, /aria-busy/);
  assert.match(page, /data-live-region/);
  assert.match(page, /aria-live="polite"/);
  assert.match(page, /data-action-failure/);
  assert.match(page, /data-action="retry-load"/);
  assert.doesNotMatch(page, /Reload the page to try again/);
  assert.doesNotMatch(page, /The API refused this action \(/, 'no raw refusal code as the only message');
});
