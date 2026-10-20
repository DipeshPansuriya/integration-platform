// T130 (row 323, F21 BS-B): one tenant's base-suite data, through the REAL API and the REAL designer only
// (plan section 2; no pipeline row is ever written here - unit/direct-sql.test.mjs scans for it):
//   source "Customer A" v1.0 + target "Target B" v2.0, both APPROVED by the tenant's approver (never the
//   author) - intake refuses a pin that is not approved (IntakeService.java:49-54); target system's base URL
//   = the tenant's path on the ONE exception host (settings.mjs targetUrl); flow "orders" (= the interface
//   code, JdbcFlowRepository.java:55-58); the mapping drawn on the designer exactly as T92 draws it
//   (tests/screens/mapping-designer.docker.spec.mjs:397-421) and its sample run passing; submit, approve
//   by the approver, publish; one source credential bound to the tenant's customer. The issued secret is
//   dropped at once - a scenario rotates the credential and keeps the new secret in memory (R07).

import { expect } from '@playwright/test';
import { targetUrl } from '../lib/settings.mjs';
import { FLOW, LINKS, SOURCE_CONTRACT, TARGET_CONTRACT, orderRecord, expectedTargetBody } from '../lib/tenants.mjs';
import { bsEnv } from './boot-state.mjs';
import { inBrowser, must } from './api.mjs';

const DESIGNER = '[data-page="mapping-designer"]';
const sourceNode = (page, field) => page.locator(`[data-tree="source"] [data-node="${field}"]`);
const targetNode = (page, field) => page.locator(`[data-tree="target"] [data-node="${field}"]`);

/** Seeds `tenant` (lib/tenants.mjs TENANTS.A / .B) as its own admin; returns ids only (no secret). */
export async function seedTenant(page, tenant, settings) {
  // every API call names its login itself; the page-wide marker is set ONLY around the designer below,
  // so an approver's call can never ride the author's marker
  await page.setExtraHTTPHeaders({});
  // an un-navigated page is an opaque origin - document.cookie throws there; land on the console first (run 0b04aa2, 20-Oct)
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const as = (method, apiPath, body, what, login = tenant.admin) => inBrowser(page, method, apiPath, body, login).then((a) => must(a, `${tenant.key}: ${what}`));
  const port = settings.port; // F1: WireMock is published on the exception port (boot-options.mjs)

  // TBD(row278|T106): the API adapter's send URL - today the production body port is empty
  // (ApiAdapterConfiguration.java:38-39) and where the delivery reads its base URL (this system setting or a
  // target-endpoint row) is the "endpoint base-URL gap" T130 step 7 waits on. Re-measure at the lock slot.
  const source = await as('POST', '/api/v1/admin/systems', {
    code: SOURCE_CONTRACT.system, name: SOURCE_CONTRACT.name, direction: 'INBOUND', type: 'API',
    settings: { baseUrl: `https://${SOURCE_CONTRACT.system}.partner.test/` }
  }, 'source system');
  const target = await as('POST', '/api/v1/admin/systems', {
    code: TARGET_CONTRACT.system, name: TARGET_CONTRACT.name, direction: 'OUTBOUND', type: 'API',
    settings: { baseUrl: targetUrl(settings, port, tenant.stubPath) }
  }, 'target system on the exception host');

  const versionIds = {};
  for (const contract of [SOURCE_CONTRACT, TARGET_CONTRACT]) {
    const { id } = await as('POST', '/api/v1/admin/contracts/versions/import', {
      system: contract.system, role: contract.role, versionLabel: contract.versionLabel, format: 'JSON_SCHEMA', document: contract.document
    }, `contract ${contract.name} v${contract.versionLabel}`);
    await as('POST', `/api/v1/admin/contracts/versions/${id}/submit`, { changeNote: `base suite ${contract.name} v${contract.versionLabel}` }, `submit ${contract.name}`);
    await as('POST', `/api/v1/admin/contracts/versions/${id}/approve`, undefined, `approve ${contract.name}`, tenant.approver);
    versionIds[contract.role] = id;
  }

  const flowBody = { code: FLOW.code, name: FLOW.name, sourceSystem: SOURCE_CONTRACT.system, destinations: [TARGET_CONTRACT.system], businessKeyRule: FLOW.businessKeyRule };
  const flow = await as('POST', '/api/v1/admin/flows', flowBody, 'flow orders');
  const routes = (await as('GET', `/api/v1/admin/flows/${flow.id}/routes`, undefined, 'routes of orders')).routes ?? [];
  expect(routes, 'single target: flow orders has exactly one route').toHaveLength(1);
  const [route] = routes;

  // The drawing, as T92 draws it - the designer's save and its "Run sample" through the real endpoints,
  // as the tenant's admin (the console proxy swaps in that login's session for the marker).
  // The browser loads the page first, so the console bundle itself never carries the marker.
  await page.goto(`${bsEnv('consoleUrl')}/`);
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': tenant.admin });
  await page.goto(`${bsEnv('consoleUrl')}/#/mappings/${route.id}`);
  await expect(page.locator(`${DESIGNER}[data-mapping-loaded="true"]`)).toBeVisible();
  await page.locator('[data-field="source-version"]').selectOption(versionIds.SOURCE_REQUEST);
  await page.locator('[data-field="target-version"]').selectOption(versionIds.TARGET_REQUEST);
  await expect(targetNode(page, 'invoiceValue')).toBeVisible();
  for (const [from, to] of LINKS) {
    await sourceNode(page, from).click();
    await targetNode(page, to).click();
    await page.click('[data-action="add-link"]');
  }
  await expect(page.locator('[data-link-row]')).toHaveCount(LINKS.length);
  await page.click('[data-action="save"]');
  await expect(page.locator('[data-saved="true"]')).toHaveText('Mapping saved.');
  const sample = orderRecord(tenant, 9000);
  await page.locator('[data-sample-input]').fill(JSON.stringify(sample));
  await page.click('[data-sample-run]');
  await expect(page.locator('[data-sample-result]')).toHaveAttribute('data-state', 'ok');
  expect(JSON.parse(await page.locator('[data-sample-output]').textContent())).toEqual(expectedTargetBody(sample));
  await page.setExtraHTTPHeaders({});

  // the flow's draft pins what it read (FlowAdminService.saveDraft), then the two-person lifecycle
  await as('PUT', `/api/v1/admin/flows/${flow.id}`, flowBody, 'draft save of orders');
  await as('POST', `/api/v1/admin/flows/${flow.id}/submit`, { changeNote: 'base suite: orders Customer A v1.0 -> Target B v2.0' }, 'submit orders');
  await as('POST', `/api/v1/admin/flows/${flow.id}/approve`, undefined, 'approve orders', tenant.approver);
  await as('POST', `/api/v1/admin/flows/${flow.id}/publish`, undefined, 'publish orders');

  const credential = await as('POST', `/api/v1/admin/systems/${source.id}/source-credentials`, { customerId: tenant.customerId }, 'source credential');
  expect(credential.customerId, 'the credential is bound to the tenant\'s own customer').toBe(tenant.customerId);
  // ids only - the secret in `credential` is dropped here
  return {
    sourceSystemId: source.id, targetSystemId: target.id, flowId: flow.id, routeId: route.id, mappingId: route.mappingId,
    credentialId: credential.id, clientId: credential.clientId
  };
}

/** A usable source secret for this scenario only: rotate the seeded credential, keep the answer in memory. */
export async function rotatedSecret(page, tenant, credentialId) {
  const rotated = must(await inBrowser(page, 'POST', `/api/v1/admin/source-credentials/${credentialId}/rotate`, undefined, tenant.admin), `${tenant.key}: rotate credential`);
  return { clientId: rotated.clientId, secretValue: rotated.secret };
}
