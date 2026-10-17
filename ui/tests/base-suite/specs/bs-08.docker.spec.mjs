// T131 (row 323, F21 BS-C) BS-08 - open the message from BS-07: every stage shown with time and duration
// >= 0, in pipeline order (WD8). The API answer (T110) and the detail screen (T112) are both checked, and
// the screen must show what the API answered.
//
// Stage names: main's INTAKE / ROUTE / TRANSFORM / DELIVER (lib/timeline.mjs). Plan FLAG: T105's criteria
// name ROUTED / TRANSFORMED / DELIVERED / FAILED - if T105 lands those names, this FAILS naming them; the
// judge (BS-G) scores T105 against its own criteria.
//
// Expected RED today, in this order:
//   1. global-setup: "dev-only exception not configured - waits for row 278" (or "still TBD(row278)");
//   2. with the exception: BS-07 leaves no DELIVERED message (T106), so the tracking id below is ACCEPTED
//      only - the timeline read 404s / has INTAKE alone (T110 and T105 not on main);
//   3. before T112: the #/messages/<id> route is not in App.tsx - the detail page never loads.
// Green needs T105, T106, T110, T112 and row 278's exception after T137. Never a psql-seeded row.

import { expect, test } from '@playwright/test';
import { fill, nameOf } from '../lib/pending-names.mjs';
import { STAGES, screenRowProblems, timelineProblems } from '../lib/timeline.mjs';
import { TENANTS } from '../lib/tenants.mjs';
import { inBrowser, must } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRun } from '../support/run-file.mjs';

test('BS-08: the message from BS-07 shows every stage with time and duration >= 0, in order', async ({ page }) => {
  const [trackingId] = readRun().tracking.A;
  expect(trackingId, "BS-07 recorded tenant A's first tracking id").toBeTruthy();
  await page.goto(`${bsEnv('consoleUrl')}/`);

  const timeline = must(await inBrowser(page, 'GET', fill(nameOf('timelineApi'), { trackingId }), undefined, TENANTS.A.admin), 'BS-08 timeline');
  expect(timelineProblems(timeline, trackingId), 'the API timeline: all four stages, ordered, timed').toEqual([]);

  await page.goto(`${bsEnv('consoleUrl')}/${fill(nameOf('detailRoute'), { trackingId })}`);
  const detail = page.locator(nameOf('detailPage'));
  await expect(detail).toHaveAttribute('data-message-loaded', 'true');
  await expect(detail.locator(nameOf('detailTrackingId'))).toHaveText(trackingId);
  await expect(detail.locator(nameOf('detailFailingStage')), 'a delivered message marks no failing stage').toHaveCount(0);

  const rows = await detail.locator(nameOf('detailStageRow')).evaluateAll((trs) => trs.map((tr) => ({
    stage: tr.getAttribute('data-stage-row'),
    status: tr.getAttribute('data-stage-status'),
    durationMs: tr.getAttribute('data-duration-ms'),
    times: [...tr.querySelectorAll('time')].map((t) => t.getAttribute('datetime'))
  })));
  expect(rows.map((r) => r.stage), 'every stage, in pipeline order').toEqual([...STAGES]);
  expect(screenRowProblems(rows, timeline), 'the screen shows what the API answered').toEqual([]);
  for (const row of rows) {
    expect(row.times, `${row.stage} shows its start and finish time`).toHaveLength(2);
  }
});
