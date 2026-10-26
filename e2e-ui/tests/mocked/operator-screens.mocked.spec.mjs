// Row 372 (UI audit): the five restyled operator screens and their silent-failure fixes, in a real browser against the
// BUILT console. No backend: page.route answers every /api call. Selectors are the screens' own data-* hooks.
import { expect, test } from '@playwright/test';

const FLOW = { id: '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e', code: 'orders', name: 'Orders', state: 'PUBLISHED' };
const TRACK = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';
const DELIVERY = '9a1c2d3e-4b5f-4a6b-8c7d-0e1f2a3b4c5d';
const json = (route, status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** Installs the fixtures; `over` maps "METHOD /path" to (route) => ... for the scenario under test. */
async function boot(page, over = {}) {
  const calls = [];
  await page.route('**/console-config.json', (r) => r.fulfill({ status: 404, body: '' }));
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url());
    const key = `${route.request().method()} ${url.pathname}`;
    calls.push(key);
    const hit = over[key];
    if (hit) return hit(route);
    if (key === 'GET /api/v1/me') return json(route, 200, { userId: 'u1', tenantId: 't1', tenantCode: 'ACME' });
    if (key === 'GET /api/v1/admin/flows') return json(route, 200, [FLOW]);
    if (key === 'GET /api/v1/admin/queue/summary') {
      return json(route, 200, [{ flowId: FLOW.id, flowCode: 'orders', flowName: 'Orders', day: '2026-10-22', queued: 3, processed: 40, converted: 38, failed: 2 }]);
    }
    if (key === 'GET /api/v1/admin/messages/failures') {
      return json(route, 200, [{ trackingId: TRACK, deliveryId: DELIVERY, flowId: FLOW.id, targetId: 'tgt', failingStage: 'DELIVER', errorClass: 'HTTP_500', lastError: 'target answered 500', origin: 'PIPELINE', failedAt: '2026-10-22T10:00:00Z' }]);
    }
    if (key === `GET /api/v1/admin/messages/${TRACK}/timeline`) {
      return json(route, 200, {
        trackingId: TRACK, flowId: FLOW.id, state: 'ROUTED', acceptedAt: '2026-10-22T09:59:00Z', totalMs: 900, failingStage: 'DELIVER',
        stages: ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER'].map((stage, i) => ({ stage, startedAt: `2026-10-22T09:59:0${i}Z`, finishedAt: `2026-10-22T09:59:0${i + 1}Z`, durationMs: 100, sincePreviousMs: 0 })),
        deliveries: [{ deliveryId: DELIVERY, targetId: 'tgt', durationMs: 120, outcome: 'HTTP_500', state: 'DEAD_LETTERED' }]
      });
    }
    if (key === 'GET /api/v1/reports/delivery') {
      return json(route, 200, [{ interfaceId: 'orders', targetId: 'tgt', successes: 10, failuresTransient: 1, failuresAmbiguous: 0, failuresPermanent: 2, retriesMade: 3, retriedThenSucceeded: 1, stillStuck: 1 }]);
    }
    if (key === 'GET /api/v1/activity') {
      return json(route, 200, { items: [{ id: 'e1', actor: 'ops@acme', action: 'REPLAY_DEAD_LETTER', targetIds: [DELIVERY], before: null, after: null, at: '2026-10-22T10:05:00Z' }], page: 0, size: 50, hasMore: false });
    }
    if (key === `POST /api/v1/admin/messages/deliveries/${DELIVERY}/replay`) return json(route, 200, {});
    return json(route, 404, { error: `unmocked ${key}` });
  });
  return calls;
}
const go = async (page, hash) => { await page.goto(`/#/${hash}`); };
const posts = (calls) => calls.filter((c) => c.startsWith('POST'));

test.describe('queue dashboard', () => {
  test('renders the figures per flow and day with one h1', async ({ page }) => {
    await boot(page); await go(page, 'queue');
    await expect(page.locator('[data-page="queue-dashboard"][data-queue-loaded="true"]')).toBeVisible();
    const row = page.locator(`[data-queue-row="${FLOW.id}"]`);
    await expect(row).toHaveAttribute('data-count-failed', '2');
    await expect(row).toHaveAttribute('data-count-queued', '3');
    await expect(page.locator('h1')).toHaveCount(1);
  });
  test('silent failure: a 500 shows a worded alert, not an empty table', async ({ page }) => {
    await boot(page, { 'GET /api/v1/admin/queue/summary': (r) => json(r, 500, { error: 'boom' }) }); await go(page, 'queue');
    await expect(page.locator('[data-refusal]')).toBeVisible();
    await expect(page.locator('[data-empty="queue-no-work"]')).toHaveCount(0);
  });
});

test.describe('failures', () => {
  test('lists the failed delivery with a link to the message and a copy control', async ({ page }) => {
    await boot(page); await go(page, 'failures');
    await expect(page.locator('[data-page="failures"][data-failures-loaded="true"]')).toBeVisible();
    await expect(page.locator(`[data-failure-row="${TRACK}"]`)).toHaveAttribute('data-failing-stage', 'DELIVER');
    await expect(page.locator(`[data-message-link="${TRACK}"]`)).toHaveAttribute('href', new RegExp(`^#/messages/${TRACK}`));
    await expect(page.locator(`[data-copy-id="${TRACK}"]`)).toBeVisible();
  });
  test('silent failure: a failed read is an alert, never "no failures"', async ({ page }) => {
    await boot(page, { 'GET /api/v1/admin/messages/failures': (r) => json(r, 500, { error: 'boom' }) }); await go(page, 'failures');
    await expect(page.locator('[data-status="failures"][role="alert"][data-refusal]')).toContainText('boom'); // the backend's own wording is shown
    await expect(page.locator('[data-empty="failures-none"]')).toHaveCount(0);
  });
  test('Replay needs a confirm step, then POSTs once and says Replay queued', async ({ page }) => {
    const calls = await boot(page); await go(page, 'failures');
    const btn = page.locator(`[data-action="replay"][data-delivery-id="${DELIVERY}"]`);
    await btn.click();
    await expect(btn).toHaveAttribute('data-state', 'confirm');
    expect(posts(calls), 'the first click only arms').toHaveLength(0);
    await btn.click();
    await expect(page.locator(`[data-action-answer="${DELIVERY}"]`)).toContainText('Replay queued');
    expect(posts(calls)).toHaveLength(1);
  });
  test('Cancel disarms the confirm step without a POST', async ({ page }) => {
    const calls = await boot(page); await go(page, 'failures');
    const btn = page.locator(`[data-action="replay"][data-delivery-id="${DELIVERY}"]`);
    await btn.click();
    await page.locator(`[data-confirm-cancel="${DELIVERY}"]`).click();
    await expect(btn).toHaveAttribute('data-state', 'ready');
    expect(posts(calls)).toHaveLength(0);
  });
});

test.describe('message detail', () => {
  test('shows the raw state, the four-stage timeline and the delivery action', async ({ page }) => {
    await boot(page); await go(page, `messages/${TRACK}`);
    await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible();
    await expect(page.locator('[data-message-state]')).toHaveAttribute('data-state-raw', 'ROUTED');
    for (const s of ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']) await expect(page.locator(`[data-timeline] tr[data-stage-row="${s}"]`)).toHaveCount(1);
    await expect(page.locator(`[data-delivery-action="${DELIVERY}"] [data-action="replay"]`)).toBeVisible();
  });
  test('silent failure: an unknown message is a worded not-found, not a blank page', async ({ page }) => {
    await boot(page, { [`GET /api/v1/admin/messages/${TRACK}/timeline`]: (r) => json(r, 404, { error: 'not found' }) }); await go(page, `messages/${TRACK}`);
    await expect(page.locator('[data-page="message-detail"]')).toBeVisible();
    await expect(page.locator('[data-message-loaded="true"]')).toHaveCount(0);
    await expect(page.locator('[data-status="message-detail"][data-empty="message-not-found"]')).not.toBeEmpty();
  });
});

test.describe('delivery report', () => {
  test('choosing a day shows the card table row', async ({ page }) => {
    await boot(page); await go(page, 'reports');
    await expect(page.locator('[data-page="delivery-report"]')).toBeVisible();
    await page.locator('[data-day-filter]').fill('2026-10-22');
    const row = page.locator('[data-report-row="orders/tgt"]');
    await expect(row).toBeVisible();
    await expect(row).toContainText('10');
  });
  test('silent failure: a 500 is an alert in plain words', async ({ page }) => {
    await boot(page, { 'GET /api/v1/reports/delivery': (r) => json(r, 500, { error: 'boom' }) }); await go(page, 'reports');
    await page.locator('[data-day-filter]').fill('2026-10-22');
    await expect(page.locator('[role="alert"][data-refusal]')).toBeVisible();
    await expect(page.locator('[data-empty="delivery-report-none"]')).toHaveCount(0);
  });
});

test.describe('activity', () => {
  test('lists events; the pager is disabled on a single page', async ({ page }) => {
    await boot(page); await go(page, 'activity');
    await expect(page.locator('[data-page="activity"][data-activity-loaded="true"]')).toBeVisible();
    await expect(page.locator('[data-activity-row="e1"]')).toBeVisible();
    await expect(page.locator('[data-action="activity-next"]')).toBeDisabled();
  });
  test('silent failure: a 500 is an alert in plain words, not "no activity"', async ({ page }) => {
    await boot(page, { 'GET /api/v1/activity': (r) => json(r, 500, { error: 'boom' }) }); await go(page, 'activity');
    await expect(page.locator('[role="alert"][data-refusal]')).toBeVisible();
    await expect(page.locator('[data-empty="activity-none"]')).toHaveCount(0);
  });
});
