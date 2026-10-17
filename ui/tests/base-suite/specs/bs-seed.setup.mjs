// T130 (row 323, F21 BS-B): the base suite's seed project (runs before every scenario project).
// Both tenants get the same flow shape through the real API and the real designer (support/seed.mjs).
// Expected RED until row 278 lands: global-setup fails closed first - "dev-only exception not configured -
// waits for row 278" / "still TBD(row278)" - so nothing here runs; with the exception configured on a
// candidate that lacks it, the target system on the exception host is refused at configuration.

import { expect, test as setup } from '@playwright/test';
import { exceptionSettings } from '../lib/settings.mjs';
import { TENANTS } from '../lib/tenants.mjs';
import { startRun } from '../support/run-file.mjs';
import { seedTenant } from '../support/seed.mjs';

setup('seed tenant A and tenant B: same flow shape, own targets on the one exception host', async ({ page }) => {
  const settings = exceptionSettings(process.env);
  const A = await seedTenant(page, TENANTS.A, settings);
  const B = await seedTenant(page, TENANTS.B, settings);
  expect(B.flowId, 'two tenants, two flows').not.toBe(A.flowId);
  startRun({ A, B });
});
