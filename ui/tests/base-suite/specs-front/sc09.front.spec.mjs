// lane qa-release (2026-10-22) SC-09 (stage timings present) - the intake leg that needs no delivery and no
// dev-only exception: every accepted message has its INTAKE stage with a start, a finish and a duration >= 0
// on the timeline API, for both tenants, each in its own flow. ROUTE / TRANSFORM / DELIVER need the local
// target (the dev-only exception) and stay in bs-08.docker.spec.mjs.

import { expect, test } from '@playwright/test';
import { intakeStageProblems } from '../lib/front-checks.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { eventually, inBrowser, postRecord, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

for (const tenant of [TENANTS.A, TENANTS.B]) {
  test(`SC-09: tenant ${tenant.key}'s accepted message has a timed INTAKE stage in its own flow`, async ({ page }) => {
    const { seed } = readRun();
    await page.goto(`${bsEnv('consoleUrl')}/`);
    const { clientId, secretValue } = await rotatedSecret(page, tenant, seed[tenant.key].credentialId);
    const answer = await postRecord(await sourceToken(clientId, secretValue), intakeCode(tenant), SOURCE_CONTRACT.versionLabel, orderRecord(tenant, 901));
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);
    const trackingId = answer.data.trackingId;
    addTracking(tenant.key, [trackingId]);

    const timeline = await eventually(async () => {
      const read = await inBrowser(page, 'GET', `/api/v1/admin/messages/${trackingId}/timeline`, undefined, tenant.admin);
      return read.status === 200 ? read.data : null;
    }, `tenant ${tenant.key}: the timeline of ${trackingId}`, { timeoutMs: 30000 });
    expect(intakeStageProblems(timeline, trackingId, seed[tenant.key].flowId), 'INTAKE: timed, on the right message and flow').toEqual([]);
  });
}
