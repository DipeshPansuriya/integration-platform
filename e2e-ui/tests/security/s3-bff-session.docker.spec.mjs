// T171 (row 372) Release 1 security gate S3 (the BFF session, ADR-007 D1), the six browser cases of the plan:
//   S3-A1 cookie flags / no token in the browser   S3-A2 CSRF on state-changing requests   S3-A3 Spring Session JDBC behaviour
//   S3-A4 logout invalidation   S3-A5 cookie tampering and replay   S3-A6 front-channel token leakage
// Against the REAL backend (Spring Session in PostgreSQL), the REAL Keycloak and the BUILT console (global-setup.mjs).
// Each case has a positive control. Test code only. S3-A3 restarts the JVM through the setup's control endpoint, so
// it is the last test of the file.

import http from 'node:http';
import crypto from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  api, assertScannerSees, atConsole, consoleCookies, consoleHost, consoleUrl, expectNoTokenInTraffic, freshLogin, otherUser,
  psql, recordTraffic, sessionCookie, sessionCookieFor, sessionIdOf, tamperedVariants, expectNulVariants, tokenFindings, JWT_PATTERN, consoleLoginEntry, appLog, signIn
} from './lib.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };
const RUN = crypto.randomBytes(3).toString('hex');

function systemBody(code) {
  return { code, name: `csrf probe ${code}`, direction: 'OUTBOUND', type: 'DB', settings: { host: 'db.partner.test', port: 5432, database: 'orders', user: 'etl' } };
}

async function systemExists(page, code) {
  const listed = await api(page, 'GET', '/api/v1/admin/systems');
  expect(listed.status).toBe(200);
  return listed.text.includes(`"${code}"`);
}

function sessionRow(id) {
  return psql(`select count(*) from console_session.spring_session where session_id = '${id}'`);
}

/** A browser context holding only `value` as its session cookie. */
async function contextWith(browser, value) {
  const context = await browser.newContext(NO_SESSION);
  if (value !== null) await context.addCookies([sessionCookieFor(value)]);
  const page = await context.newPage();
  await atConsole(page);
  return { context, page };
}

test.describe('S3 BFF session (browser)', () => {
  test('S3-A1 cookie flags: HttpOnly, Secure, SameSite=Lax, and no access or refresh token anywhere in the browser', async ({ browser }) => {
    assertScannerSees();
    const context = await browser.newContext(NO_SESSION);
    const page = await context.newPage();
    const traffic = recordTraffic(page);
    await signIn(page);
    await expect(page.locator('[data-session-tenant]')).toBeVisible();
    await page.goto(`${consoleUrl()}/#/systems`);
    await traffic.settle();

    // the raw Set-Cookie of the session cookie, as the backend sent it (not the browser's re-rendering of it)
    const setCookies = [];
    for (const response of traffic.responses) {
      if (new URL(response.url).hostname !== consoleHost()) continue;
      for (const line of response.setCookies) if (line.startsWith('SESSION=')) setCookies.push(line);
    }
    expect(setCookies.length, 'the login set a SESSION cookie').toBeGreaterThan(0);
    for (const line of setCookies) {
      expect(line, 'HttpOnly').toMatch(/;\s*HttpOnly/i);
      expect(line, 'Secure').toMatch(/;\s*Secure/i);
      expect(line, 'SameSite=Lax').toMatch(/;\s*SameSite=Lax/i);
    }
    const session = await sessionCookie(context);
    expect([session.httpOnly, session.secure, session.sameSite], 'the browser stored it with the same flags').toEqual([true, true, 'Lax']);
    const cookies = await consoleCookies(context);
    for (const cookie of cookies) expect(JWT_PATTERN.test(cookie.value), `cookie ${cookie.name} holds no JWT`).toBe(false);
    expect(await page.evaluate(() => document.cookie), 'script cannot read the session cookie').not.toContain('SESSION=');

    const stored = await page.evaluate(() => JSON.stringify({ local: Object.entries(localStorage), session: Object.entries(sessionStorage), name: window.name }));
    expect(tokenFindings(stored), 'no token in localStorage, sessionStorage or window.name').toEqual([]);
    await traffic.settle();
    expectNoTokenInTraffic(traffic); // cookies, the /callback exchange and every API answer carry no token; every body and header read delivered
    await context.close();
  });

  test('S3-A2 CSRF: a state-changing request without or with a forged token is refused, and SameSite=Lax blocks the cross-site send', async ({ browser }) => {
    const { context, page } = await freshLogin(browser);
    await atConsole(page);
    expect((await api(page, 'GET', '/api/v1/me')).status, 'control: the session works').toBe(200);

    const noToken = `csrf-none-${RUN}`;
    const forgedToken = `csrf-forged-${RUN}`;
    const foreignToken = `csrf-foreign-${RUN}`;
    const accepted = `csrf-ok-${RUN}`;
    expect((await api(page, 'POST', '/api/v1/admin/systems', { body: systemBody(noToken), csrf: 'none' })).status, 'POST without the token').toBe(403);
    expect((await api(page, 'POST', '/api/v1/admin/systems', { body: systemBody(forgedToken), csrf: 'forged', token: crypto.randomUUID() })).status, 'POST with a forged token').toBe(403);

    // a genuine token, but of ANOTHER signed-in session
    const other = await freshLogin(browser, otherUser());
    await atConsole(other.page);
    await api(other.page, 'GET', '/api/v1/me');
    const foreign = (await consoleCookies(other.context)).find((cookie) => cookie.name === 'XSRF-TOKEN').value;
    expect((await api(page, 'POST', '/api/v1/admin/systems', { body: systemBody(foreignToken), csrf: 'forged', token: foreign })).status, 'POST with another session\'s token').toBe(403);
    await other.context.close();

    const created = await api(page, 'POST', '/api/v1/admin/systems', { body: systemBody(accepted) });
    expect(created.status, 'control: the same POST with the session\'s own token is accepted').toBe(201);
    const systemId = created.data.id;
    for (const method of ['PUT', 'PATCH', 'DELETE']) {
      expect((await api(page, method, `/api/v1/admin/systems/${systemId}`, { body: systemBody(accepted), csrf: 'none' })).status, `${method} without the token`).toBe(403);
    }
    expect((await api(page, 'GET', `/api/v1/admin/systems/${systemId}`)).status, 'the system survived every refused change').toBe(200);
    for (const code of [noToken, forgedToken, foreignToken]) expect(await systemExists(page, code), `${code} was not created`).toBe(false);

    // cross-site: a page on another SITE (localhost vs 127.0.0.1) submits a form to the console API. SameSite=Lax holds the
    // session cookie back, so the API sees no session (401). Control: the same form from a same-site page carries the
    // cookie and meets the CSRF check (403) - proving the 401 is the cookie rule, not a broken fixture.
    const target = `${consoleUrl()}/api/v1/admin/systems`;
    const attacker = http.createServer((_request, response) => {
      response.writeHead(200, { 'content-type': 'text/html' });
      response.end(`<form id="f" method="POST" action="${target}"><input name="code" value="csrf-cross-${RUN}"></form><script>document.getElementById('f').submit()</script>`);
    });
    await new Promise((resolve) => attacker.listen(0, '127.0.0.1', resolve));
    const port = attacker.address().port;
    async function submitFrom(host) {
      const probe = page; // the signed-in page itself: a second page in this headless shell is not creatable once contexts churn
      const answered = probe.waitForEvent('response', { predicate: (r) => r.url() === target && r.request().method() === 'POST', timeout: 20000 });
      probe.goto(`http://${host}:${port}/`).catch(() => {});
      try {
        return (await answered).status();
      } catch {
        throw new Error(`the form post from ${host} got no answer; page is at ${probe.url()}`);
      } finally {
        await probe.waitForLoadState('load').catch(() => {});
        await atConsole(probe);
      }
    }
    try {
      expect(await submitFrom('127.0.0.1'), 'control: a same-site form carries the session cookie and is stopped by CSRF').toBe(403);
      expect(await submitFrom('localhost'), 'a cross-site form carries no session cookie').toBe(401);
    } finally {
      await new Promise((resolve) => attacker.close(resolve));
    }
    expect(await systemExists(page, `csrf-cross-${RUN}`)).toBe(false);
    await context.close();
  });

  test('S3-A4 logout invalidation: the old cookie no longer works and the session row is gone', async ({ browser }) => {
    const { context, page } = await freshLogin(browser);
    const value = (await sessionCookie(context)).value;
    const id = sessionIdOf(value);
    expect(sessionRow(id), 'control: the session is a row in PostgreSQL').toBe('1');
    const beforeLogout = await contextWith(browser, value);
    expect((await api(beforeLogout.page, 'GET', '/api/v1/me')).status, 'control: replaying the cookie in another browser works while the session lives').toBe(200);
    await beforeLogout.context.close();

    await page.click('[data-action="account"]');
    await page.click('[data-action="logout"]');
    await page.waitForURL((url) => url.toString().startsWith(consoleUrl()) && url.hash === '#/login');
    await expect(page.locator('[data-action="sso"]')).toBeVisible();

    expect(sessionRow(id), 'the Spring Session row is invalidated').toBe('0');
    expect(psql(`select count(*) from console_session.spring_session_attributes a where not exists (select 1 from console_session.spring_session s where s.primary_id = a.session_primary_id)`), 'no orphaned attribute rows').toBe('0');
    const replay = await contextWith(browser, value);
    expect((await api(replay.page, 'GET', '/api/v1/me')).status, 'the old cookie after logout').toBe(401);
    const write = await api(replay.page, 'POST', '/api/v1/admin/systems', { body: systemBody(`after-logout-${RUN}`), csrf: 'forged', token: crypto.randomUUID() });
    expect([401, 403], 'and it cannot write').toContain(write.status);
    await replay.context.close();
    await context.close();
  });

  // B33 (fixed on main, 03a055c1): a tampered SESSION cookie is refused with 401 before the session lookup, so it neither
  // answers 500 nor leaks a database connection. The test asserts what the fix guarantees: every tampered variant is 401, the
  // pool still serves a genuine login afterwards, and no pool error reached the application log. No restart in between - a
  // restart would hide a leak.
  test('S3-A5 cookie tampering and replay: tampered cookies are refused, a replayed cookie stays in its own tenant, a login always issues a new session id', async ({ browser }) => {
    const a = await freshLogin(browser);
    const valueA = (await sessionCookie(a.context)).value;
    const b = await freshLogin(browser, otherUser());
    const valueB = (await sessionCookie(b.context)).value;

    // tampered cookies: none authenticates, none mints a session that does
    const tampered = tamperedVariants(valueA);
    expectNulVariants(tampered);
    const poolErrorsBefore = await appLog('Could not open JDBC Connection');
    let unresponsive = null;
    for (const [label, value] of Object.entries(tampered)) {
      const probe = await contextWith(browser, value);
      const me = await api(probe.page, 'GET', '/api/v1/me');
      expect.soft(me.status, `${label}: refused with 401 (not 500) (answer: ${me.text.slice(0, 60).replace(/\s+/g, ' ')})`).toBe(401);
      expect.soft(me.text, `${label}: no tenant data in the answer`).not.toContain(process.env.CONSOLE_TENANT_CODE);
      await probe.context.close();
      if (me.status === 0) {
        unresponsive = label;
        break; // the application no longer answers: no point hammering it
      }
    }
    // B33 guarantee: all 13 variants (incl. the three NUL-bearing ones, T202) were refused with 401 (never 500) and the pool is not exhausted.
    expect(unresponsive, 'the application kept answering during the tampered requests').toBeNull();
    expect((await appLog('Could not open JDBC Connection')).length, 'no pool exhaustion errors in the application log').toBe(poolErrorsBefore.length);
    // pool check: a genuine login and GET /api/v1/me still work afterwards (no leaked connections), on the same JVM
    const pool = await freshLogin(browser);
    const poolMe = await api(pool.page, 'GET', '/api/v1/me');
    expect(poolMe.status, 'pool check: a genuine login then GET /api/v1/me returns 200 after the tampered cookies').toBe(200);
    expect(poolMe.data.tenantCode).toBe(process.env.CONSOLE_TENANT_CODE);
    await pool.context.close();

    console.log(`S3-A5 application log after the tampered cookies:\n${(await appLog('Exception|HTTP Status|session')).split('\n').map((line) => line.slice(0, 400)).join('\n')}`);

    // replay: a copied cookie resolves exactly its own tenant, whoever holds it
    const replayA = await contextWith(browser, valueA);
    const meA = await api(replayA.page, 'GET', '/api/v1/me');
    expect(meA.status, 'control: the genuine cookie replayed').toBe(200);
    expect(meA.text).toContain(process.env.CONSOLE_TENANT_CODE);
    expect(meA.data.tenantCode, 'tenant A cookie resolves tenant A and no other').toBe(process.env.CONSOLE_TENANT_CODE);
    expect((await api(replayA.page, 'GET', '/api/v1/admin/flows')).text).not.toContain(process.env.CONSOLE_OTHER_TENANT_FLOW);
    await replayA.context.close();
    const replayB = await contextWith(browser, valueB);
    const meB = await api(replayB.page, 'GET', '/api/v1/me');
    expect(meB.status).toBe(200);
    expect(meB.text).toContain(process.env.SEC_OTHER_TENANT_CODE);
    expect(meB.data.tenantCode, 'tenant B cookie resolves tenant B and no other').toBe(process.env.SEC_OTHER_TENANT_CODE);
    await replayB.context.close();

    // fixation: an attacker's pre-login session id, planted in the victim's browser, is not the session after login
    const attacker = await browser.newContext(NO_SESSION);
    const attackerPage = await attacker.newPage();
    await attackerPage.goto(consoleLoginEntry());
    await attackerPage.locator('#kc-form-login').waitFor();
    const planted = await sessionCookie(attacker);
    const victim = await browser.newContext(NO_SESSION);
    if (planted) await victim.addCookies([sessionCookieFor(planted.value)]);
    const victimPage = await victim.newPage();
    await signIn(victimPage);
    await expect(victimPage.locator('[data-session-tenant]')).toBeVisible();
    const after = await sessionCookie(victim);
    expect(planted, 'control: the login entry opened a pre-login session whose id the attacker holds').toBeTruthy();
    expect(after.value, 'the session id after login is new').not.toBe(planted.value);
    expect(sessionIdOf(after.value)).not.toBe(sessionIdOf(planted.value));
    await atConsole(attackerPage);
    expect((await api(attackerPage, 'GET', '/api/v1/me')).status, 'the planted id does not see the victim\'s login').toBe(401);

    await Promise.all([attacker.close(), victim.close(), a.context.close(), b.context.close()]);
  });

  test('S3-A6 front-channel token leakage: no token in any URL, Referer, redirect or response body; the code is exchanged server-side only', async ({ browser }) => {
    assertScannerSees();
    const context = await browser.newContext(NO_SESSION);
    const page = await context.newPage();
    const traffic = recordTraffic(page);
    await signIn(page);
    await expect(page.locator('[data-session-tenant]')).toBeVisible();
    for (const screen of ['versions', 'flows', 'systems', 'contracts', 'failures']) {
      await traffic.quiesce(); // T211: the previous screen's reads finish before this navigation could cut them off
      await page.goto(`${consoleUrl()}/#/${screen}`);
      await expect(page.locator(`[data-page="${screen}"]`).or(page.locator('[data-page]').first())).toBeVisible();
    }
    await traffic.quiesce(); // the full navigation below used to cut off a streaming /api/v1/admin/flows body (the S3-A6 hang)
    await atConsole(page);
    await api(page, 'GET', '/api/v1/me');
    await traffic.settle(); // bounded (T211): a read that never answers cannot hang it; expectNoTokenInTraffic then fails on it
    const urls = [...traffic.navigations, page.url(), ...(await page.evaluate(() => performance.getEntriesByType('resource').map((entry) => entry.name)))];
    expectNoTokenInTraffic(traffic, urls);
    expect(await page.evaluate(() => document.referrer), 'no referrer carries a token').not.toMatch(JWT_PATTERN);

    // the code arrives at the backend (callback on the backend, not at a browser script) and the browser never calls the token endpoint
    const tokenCalls = traffic.requests.filter((request) => request.url.includes('/protocol/openid-connect/token'));
    expect(tokenCalls, 'the browser never calls the realm\'s token endpoint').toEqual([]);
    const callbacks = traffic.requests.filter((request) => new URL(request.url).pathname === '/login/oauth2/code/keycloak'); // by path: the authorization URL repeats the text in redirect_uri
    expect(callbacks.length, 'control: the code callback was seen').toBeGreaterThan(0);
    for (const callback of callbacks) {
      const query = new URL(callback.url).searchParams;
      expect([...query.keys()].sort(), 'the callback carries a code and state, no token').toEqual(expect.arrayContaining(['code', 'state']));
      expect([...query.keys()].some((name) => /token/i.test(name))).toBe(false);
      expect(new URL(callback.url).hash).toBe('');
    }
    const loginDocuments = traffic.responses.filter((response) => new URL(response.url).pathname === '/login/oauth2/code/keycloak');
    for (const response of loginDocuments) expect(response.status, 'the callback answers with a redirect, not a page that holds data').toBe(302);
    await context.close();
  });

  // Restarts the JVM: keep last. The session lives in PostgreSQL, not in the instance.
  test('S3-A3 Spring Session JDBC: the session is a PostgreSQL row and survives an application restart without duplication', async ({ browser }) => {
    const { context, page } = await freshLogin(browser);
    const value = (await sessionCookie(context)).value;
    const id = sessionIdOf(value);
    expect(id, 'the cookie is the base64 of a session id').toMatch(/^[0-9a-f-]{36}$/);

    expect(sessionRow(id), 'one row for the session').toBe('1');
    const attributes = Number(psql(`select count(*) from console_session.spring_session_attributes a join console_session.spring_session s on s.primary_id = a.session_primary_id where s.session_id = '${id}'`));
    expect(attributes, 'the login\'s security context is stored with it').toBeGreaterThan(0);
    const rowsBefore = Number(psql('select count(*) from console_session.spring_session'));
    const xsrfBefore = (await consoleCookies(context)).find((cookie) => cookie.name === 'XSRF-TOKEN').value;
    await atConsole(page);
    const meBefore = await api(page, 'GET', '/api/v1/me');
    expect(meBefore.status).toBe(200);

    const restarted = await fetch(`${process.env.SEC_CONTROL}/restart`, { method: 'POST', headers: { 'x-control-token': process.env.SEC_CONTROL_TOKEN }, signal: AbortSignal.timeout(170000) });
    expect(await restarted.text(), 'the application restarted').toBe('restarted');

    await atConsole(page);
    const meAfter = await api(page, 'GET', '/api/v1/me');
    expect(meAfter.status, 'the same cookie is still a session on the new instance').toBe(200);
    expect(meAfter.text).toBe(meBefore.text);
    expect((await sessionCookie(context)).value, 'no new session id was issued').toBe(value);
    expect(sessionRow(id), 'still exactly one row for it').toBe('1');
    expect(Number(psql('select count(*) from console_session.spring_session')), 'the restart created no extra sessions').toBe(rowsBefore);
    const write = await api(page, 'POST', '/api/v1/admin/systems', { body: systemBody(`survives-${RUN}`) });
    expect(write.status, 'and a state-changing call still passes CSRF on the new instance').toBe(201);
    expect((await consoleCookies(context)).find((cookie) => cookie.name === 'XSRF-TOKEN').value, 'the CSRF token cookie is unchanged').toBe(xsrfBefore);
    await context.close();
  });
});
