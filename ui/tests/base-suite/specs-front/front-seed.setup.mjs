// lane qa-release (2026-10-22): the front half's seed (runs before every front spec). Both tenants get the
// base suite's flow shape through the real API and the real designer (support/seed.mjs seedTenant), with the
// target system saved on the ONE allow-listed host of the normal allow-list - no dev-only exception, no
// local target (lib/front-options.mjs). No pipeline row is written here.

import { expect, test as setup } from '@playwright/test';
import { FRONT_TARGET_BASE } from '../lib/front-options.mjs';
import { TENANTS } from '../lib/tenants.mjs';
import { startRun } from '../support/run-file.mjs';
import { seedTenant } from '../support/seed.mjs';

setup('front seed: tenant A and tenant B, same flow shape, target saved on the allow-listed host', async ({ page }) => {
  const seed = (tenant) => seedTenant(page, tenant, undefined, { targetBaseUrl: `${FRONT_TARGET_BASE}${tenant.stubPath}` });
  const A = await seed(TENANTS.A);
  const B = await seed(TENANTS.B);
  expect(B.flowId, 'two tenants, two flows').not.toBe(A.flowId);
  startRun({ A, B });
});
