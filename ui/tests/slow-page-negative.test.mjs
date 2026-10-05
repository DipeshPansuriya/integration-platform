// @negative scenario (E3-F1-S2, T-096): "A slow page fails the build - Given a page whose p95
// is 1.5 s or more, When the performance test runs, Then it fails and names the page."
//
// Proven with a SYNTHETIC slow page: a local HTTP server that delays its response past the
// budget. No database seeding is needed for this half (plan decision). The test uses the same
// measurement engine the real gate (npm run perf) uses, so a slow admin page would fail the
// build the same way.

import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { runP95Check, formatReport, P95_BUDGET_MS } from '../scripts/measure-p95.mjs';

async function startSlowPageServer() {
  const server = http.createServer((req, res) => {
    // a deliberately slow page: its p95 lands at or above the budget
    setTimeout(() => {
      res.setHeader('content-type', 'text/html');
      res.end('<!doctype html><html><body><main data-page="synthetic-slow">slow</main></body></html>');
    }, P95_BUDGET_MS + 150);
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return { url: `http://127.0.0.1:${server.address().port}`, close: () => server.close() };
}

test('@negative: a page whose p95 is at or above 1.5 s fails the run and the failure names the page', async () => {
  const slowServer = await startSlowPageServer();
  try {
    const result = await runP95Check({ pages: [{ name: 'synthetic-slow', path: '/slow' }] }, { base: slowServer.url, loads: 8 });
    assert.equal(result.failures.length, 1, 'the slow page fails the run');
    assert.equal(result.failures[0].name, 'synthetic-slow', 'the failure names the page');
    assert.ok(result.failures[0].p95 >= P95_BUDGET_MS, `the named page's p95 is >= ${P95_BUDGET_MS} ms (measured ${result.failures[0].p95})`);
    const report = formatReport(result);
    assert.ok(report.includes('synthetic-slow'), `the report names the page: ${report.split('\n').pop()}`);
  } finally {
    slowServer.close();
  }
});

test('@negative: a page under the budget does not fail (the engine cannot be fooled by a fast page)', async () => {
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'text/html');
    res.end('<!doctype html><html><body><main data-page="fast">fast</main></body></html>');
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  try {
    const result = await runP95Check({ pages: [{ name: 'fast-page', path: '/fast' }] }, { base: `http://127.0.0.1:${server.address().port}`, loads: 8 });
    assert.equal(result.failures.length, 0, 'no failures for a page under the budget');
  } finally {
    server.close();
  }
});