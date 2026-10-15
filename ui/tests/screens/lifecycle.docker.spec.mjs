// T-022 (E3 lane D, row 278) - the version + approval screens, FAILING FIRST.
//
// Scenario "The lifecycle": a draft flow version is submitted (changeNote body - the T-093
// contract), approved by ANOTHER user, and published; the published bundle equals the snapshot
// taken at submit, byte for byte. Author-never-approver is enforced server-side; the screen
// shows the refusal, never hides it (E3-F2-S1). All against the REAL API - no mock (E3-F3-S1).
//
// RED at this tip: the screens do not exist and lane C's T-019/T-021 lifecycle API is not
// merged into row278-integration yet.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

test.beforeEach(async ({ page }) => {
  await page.goto(consoleUrl());
});

test('submit for approval sends the changeNote body through the real API and lists the submitted version', async ({ page }) => {
  await openFreshDraft(page);

  await page.fill('[data-field="change-note"]', 'first version of the orders flow');
  await page.click('[data-action="submit-for-approval"]');

  await expect(page.locator('[data-version-status="SUBMITTED"]').first()).toBeVisible();
});

test('the author cannot approve: the refusal is shown on the screen, never hidden', async ({ page }) => {
  await openFreshDraft(page);
  await page.click('[data-action="submit-for-approval"]'); // the current user is the author
  await page.click('[data-action="approve"]');

  await expect(page.locator('[data-refusal="author-cannot-approve"]')).toBeVisible();
});

test('another user approves and the publish screen offers the approved version', async ({ page }) => {
  await openFreshDraft(page);
  await page.click('[data-action="submit-for-approval"]');
  // the second seeded user (an approver who is not the author) approves through the API; the
  // console's approve screen is where the approver acts on their own login - the harness's
  // second binding drives it
  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator('[data-version-status="APPROVED"]').first()).toBeVisible();
  await expect(page.locator('[data-action="publish"]')).toBeEnabled();
});

test('the published bundle equals the snapshot taken at submit, byte for byte, on the publish screen', async ({ page }) => {
  // T83: the publish gate (ADR-011 rule 6 + T-037) only lets a flow whose mapping is bound to contract versions and
  // sample-run publish - so the walked flow is one set up exactly as a user would before publishing.
  const seed = await seedPublishableContracts(page);
  const flowId = (await inBrowser(page, 'POST', '/api/v1/admin/flows', seed.flowBody)).data?.id;
  if (!flowId) throw new Error('the publishable draft was refused');
  await bindMappings(page, flowId, seed);

  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${flowId}"]`);
  await page.click('[data-action="submit-for-approval"]');
  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="APPROVED"]`)).toBeVisible();
  await runSampleRuns(page, flowId); // T-037: the walked flow's mapping sample run, before publish
  await page.click('[data-action="publish"]');

  await expect(page.locator('[data-published="true"]')).toBeVisible();
  await expect(page.locator('[data-bundle-equals-snapshot="true"]')).toBeVisible(); // byte-for-byte compare rendered by the screen
});

// Row 310 T88 (10-screens §4): the lifecycle panel offers only the actions valid for the status, so a
// submitted version shows no Save draft at all; the immutability is stated and New draft is the way.
test('a submitted version cannot be edited: Save draft is not offered, a new draft is the only way', async ({ page }) => {
  const flowId = await openFreshDraft(page);
  await page.click('[data-action="submit-for-approval"]');
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="SUBMITTED"]`)).toBeVisible();

  await expect(page.locator('[data-action="save-draft"]')).toHaveCount(0);
  await expect(page.locator('[data-notice="submitted-version-immutable"]')).toBeVisible();
  await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
});

// V-1 (row 306 defect batch, lane A): a row click must make THAT version the selected one.
// Before the fix every action resolved through the screen's single selectedRef, pinned at
// mount to the FIRST draft row - so acting after clicking another row walked the pinned one
// instead (click row 2, press Approve, row 1 gets approved). Two fresh drafts are created
// through the real API (the browser's own signed-in session, like the console's typed client):
// the SECOND one is the row the click selects; the first-draft row is the row the unfixed
// screen stays pinned to. The proof is SERVER truth on a fresh mount: only the clicked row
// walked the lifecycle, the pinned first-draft row never moved.
test('submit, approve and publish walk the row that was clicked, never the pinned first draft', async ({ page }) => {
  const seed = await seedPublishableContracts(page); // T83: the clicked flow is one the publish gate can pass
  const { pinnedId, clickedId } = await page.evaluate(async ({ url, publishable }) => {
    const read = async (method, path, body) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const answer = await fetch(url + path, {
        method,
        headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.split('=').pop()) } : {}) },
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined
      });
      return { status: answer.status, data: answer.status === 0 ? null : await answer.json().catch(() => null) };
    };
    const draft = { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' };
    const first = (await read('POST', '/api/v1/admin/flows', draft)).data;
    const second = (await read('POST', '/api/v1/admin/flows', publishable)).data;
    const rows = (await read('GET', '/api/v1/admin/flows')).data ?? [];
    const pinned = rows.find((row) => row.status === 'DRAFT');
    if (!first?.id || !second?.id || !pinned) throw new Error('the new drafts were refused or no draft row lists');
    if (pinned.id === second.id) throw new Error('the clicked row is also the first draft - suite state assumption broken');
    return { pinnedId: pinned.id, clickedId: second.id };
  }, { url: consoleUrl(), publishable: seed.flowBody });
  await bindMappings(page, clickedId, seed); // the user binds the clicked flow's mapping before submitting it

  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${clickedId}"]`); // the user's row - NOT the pinned first-draft row

  await page.fill('[data-field="change-note"]', 'the clicked row, never the pinned first draft');
  await page.click('[data-action="submit-for-approval"]');
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="SUBMITTED"]`)).toBeVisible();

  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="APPROVED"]`)).toBeVisible();

  await runSampleRuns(page, clickedId); // T-037: the clicked flow's mapping sample run, before publish
  await page.click('[data-action="publish"]');
  await expect(page.locator('[data-published="true"]')).toBeVisible();
  await expect(page.locator('[data-bundle-equals-snapshot="true"]')).toBeVisible(); // the clicked flow's own submit snapshot

  // A fresh mount re-lists from the server: the clicked row is PUBLISHED, the pinned
  // first-draft row was never acted on and still reads DRAFT.
  await page.click('[data-nav="flows"]');
  await page.click('[data-nav="versions"]');
  await expect(page.locator(`[data-flow-row="${pinnedId}"] [data-version-status="DRAFT"]`)).toBeVisible();
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="PUBLISHED"]`)).toBeVisible();
});
/** One call through the browser's own signed-in session (the console's cookie + XSRF), like the typed client. */
async function inBrowser(page, method, apiPath, body, asLogin) {
  return page.evaluate(
    async ({ method, apiPath, body, asLogin }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      if (asLogin) headers['X-Harness-Login'] = asLogin;
      const answer = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body, asLogin }
  );
}

const must = (answer, what) => {
  if (!(answer.status === 200 || answer.status === 201)) throw new Error(`seed ${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  return answer.data;
};

/**
 * What a user sets up before a flow can publish (mapping-designer spec's pattern): a fresh source and target system,
 * the source's SOURCE_REQUEST contract version (the one a new flow on that source pins) and the target's
 * TARGET_REQUEST version, approved by the harness approver - never the author.
 */
async function seedPublishableContracts(page) {
  const run = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const source = `src-life-${run}`;
  const target = `tgt-life-${run}`;
  for (const [code, direction] of [[source, 'INBOUND'], [target, 'OUTBOUND']]) {
    must(await inBrowser(page, 'POST', '/api/v1/admin/systems', { code, name: code, direction, type: 'API', settings: { baseUrl: `https://${code}.partner.test/` } }), `system ${code}`);
  }
  const schema = (field) => ({ $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', properties: { [field]: { type: 'string' } } });
  const imported = async (system, role, field) => must(await inBrowser(page, 'POST', '/api/v1/admin/contracts/versions/import',
    { system, role, versionLabel: '1.0', format: 'JSON_SCHEMA', document: schema(field) }), `contract ${system}/${role}`).id;
  const sourceVersionId = await imported(source, 'SOURCE_REQUEST', 'orderId');
  const targetVersionId = await imported(target, 'TARGET_REQUEST', 'order_number');
  must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${targetVersionId}/submit`, { changeNote: 'ready for mapping' }), 'submit target');
  must(await inBrowser(page, 'POST', `/api/v1/admin/contracts/versions/${targetVersionId}/approve`, undefined, 'approver'), 'approve target');
  return { sourceVersionId, targetVersionId, flowBody: { sourceSystem: source, destinations: [target], businessKeyRule: 'orderId' } };
}

/** Binds every route's mapping of the flow to the seeded source + target versions (the designer's save, PUT), then saves the flow's draft. */
async function bindMappings(page, flowId, seed) {
  const routes = must(await inBrowser(page, 'GET', `/api/v1/admin/flows/${flowId}/routes`), `routes of ${flowId}`).routes ?? [];
  if (routes.length === 0) throw new Error(`flow ${flowId} lists no routes to bind`);
  for (const route of routes) {
    const live = must(await inBrowser(page, 'GET', `/api/v1/admin/routes/${route.id}/mapping`), `mapping of route ${route.id}`);
    must(await inBrowser(page, 'PUT', `/api/v1/admin/routes/${route.id}/mapping`, {
      sourceContractVersionId: seed.sourceVersionId,
      targetContractVersionId: seed.targetVersionId,
      fieldMappings: [],
      compiledExpression: live.compiledExpression
    }), `bind route ${route.id}`);
  }
  // A flow version pins what its draft save read (FlowAdminService.saveDraft): the user saves the flow's draft
  // after binding, so the version they submit carries the bound mapping.
  must(await inBrowser(page, 'PUT', `/api/v1/admin/flows/${flowId}`, seed.flowBody), `draft save of ${flowId}`);
}

/**
 * The T-037 sample-run gate, satisfied the way a real user does (no console screen runs it yet): every route of the
 * flow's current version names its mapping (GET /flows/{id}/routes, T81) and that mapping's sample run goes through
 * the real endpoint (POST /mappings/{id}/test). A run that does not PASS stops the spec - the gate is never bypassed.
 */
async function runSampleRuns(page, flowId) {
  const listed = await inBrowser(page, 'GET', `/api/v1/admin/flows/${flowId}/routes`);
  const routes = listed.data?.routes ?? [];
  if (listed.status !== 200 || routes.length === 0) throw new Error(`no routes listed for flow ${flowId} (${listed.status})`);
  for (const route of routes) {
    if (!route.mappingId) throw new Error(`route ${route.id} of flow ${flowId} names no mapping to sample-run`);
    const run = await inBrowser(page, 'POST', `/api/v1/admin/mappings/${route.mappingId}/test`, { sample: { customer_id: 'C-1' } });
    if (run.status !== 200 || run.data?.status !== 'OK') {
      throw new Error(`the sample run of mapping ${route.mappingId} did not pass: ${run.status} ${JSON.stringify(run.data)}`);
    }
  }
}

// T83 (row 278, close E7): a publish the SERVER refuses is surfaced on the Versions screen, never
// hidden. A fresh draft's mapping has never been sample-run, so the real publish gate (T-037,
// MappingPublishPinCheck) answers 409 with a named reason; the screen shows that reason and the
// version stays APPROVED - nothing claims it was published.
test('a publish the server refuses shows the refusal on the screen and the version is not published', async ({ page }) => {
  const created = await inBrowser(page, 'POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
  const flowId = created.data?.id;
  if (!flowId) throw new Error(`the fresh draft was refused (${created.status})`);

  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${flowId}"]`);
  await page.fill('[data-field="change-note"]', 'a publish the gate refuses');
  await page.click('[data-action="submit-for-approval"]');
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="SUBMITTED"]`)).toBeVisible();
  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="APPROVED"]`)).toBeVisible();

  await page.click('[data-action="publish"]');

  const refusal = page.locator('[data-refusal="publish"]');
  await expect(refusal).toBeVisible();
  await expect(refusal).toContainText(`publish refused on flow ${flowId}`); // the 409 body's own text
  await expect(page.locator('[data-published="true"]')).toHaveCount(0);
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="APPROVED"]`)).toBeVisible();
});

/**
 * Row 310 T88 (10-screens §4): the Versions screen no longer creates a flow by itself, so each walk
 * seeds its own draft through the browser's signed-in session (the body the old auto-create used)
 * and selects that row - the walk never depends on which draft another spec left first.
 */
async function openFreshDraft(page) {
  const flowId = must(await inBrowser(page, 'POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' }), 'draft flow').id;
  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${flowId}"]`);
  await expect(page.locator(`[data-flow-row="${flowId}"] [data-version-status="DRAFT"]`)).toBeVisible();
  return flowId;
}
