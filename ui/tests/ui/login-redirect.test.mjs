// Row 571: no "Continue with SSO" page. A visitor with no session goes straight to the identity provider;
// the loop guard turns a second no-session arrival (Back, cancelled or failed login) into a "Try again" page;
// the sign-out landing shows a Sign in button; the screen asked for is remembered across the round trip.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/login-redirect.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { findAll, mount } from './hook-harness.mjs';

const flow = await import('../../src/pages/login-flow.ts');
const { decideLoginView, sanitizeReturnTo, landingAfterLogin, ATTEMPT_TTL_MS } = flow;

const NOW = 1_000_000_000_000;
const memory = (over = {}) => ({ attemptAt: null, loggedOut: false, storageOk: true, now: NOW, ...over });
const by = (screen, name, value) => findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));
const textOf = (node) => (Array.isArray(node) ? node.map(textOf).join('') : typeof node === 'object' && node ? textOf(node.props?.children) : String(node ?? ''));

function fakeStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return { map, getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => void map.set(k, String(v)), removeItem: (k) => void map.delete(k) };
}

// ---------- the redirect decision (pure) ----------
test('signed out, first visit: redirect once', () => {
  assert.equal(decideLoginView({ state: 'signed-out', ...memory() }), 'redirect');
});

test('still checking the session: wait, never redirect', () => {
  assert.equal(decideLoginView({ state: 'checking', ...memory() }), 'wait');
});

test('a failed session check shows the error page, never a redirect', () => {
  assert.equal(decideLoginView({ state: 'error', ...memory() }), 'session-error');
});

test('loop guard: a recent attempt that comes back signed out is "failed", not another redirect', () => {
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ attemptAt: NOW - 5_000 }) }), 'failed');
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ attemptAt: NOW + 60_000 }) }), 'failed', 'a future stamp is not trusted');
});

test('an old attempt is forgotten: a new visit redirects again (one jump per visit, no tight loop)', () => {
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ attemptAt: NOW - ATTEMPT_TTL_MS - 1 }) }), 'redirect');
});

test('just signed out: the landing shows a button, even with an old attempt on record', () => {
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ loggedOut: true, attemptAt: NOW - 1_000 }) }), 'logged-out');
});

test('no usable storage means no loop guard, so no automatic redirect: a button decides', () => {
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ storageOk: false }) }), 'prompt');
});

// ---------- return-to path ----------
test('return-to keeps real screen hashes only', () => {
  assert.equal(sanitizeReturnTo('#/versions'), '#/versions');
  assert.equal(sanitizeReturnTo('#/messages/abc-123'), '#/messages/abc-123');
  for (const bad of [null, undefined, '', '#', '#/', '#/login', '#/login?x=1', 'https://evil.example/', '//evil.example', 'javascript:alert(1)', '#/a b', '#/<script>', `#/${'a'.repeat(600)}`]) {
    assert.equal(sanitizeReturnTo(bad), null, String(bad));
  }
});

test('return-to (row 571 follow-up 4): a query on a real route survives; second slash, case and encoded tricks do not', () => {
  assert.equal(sanitizeReturnTo('#/failures?flow=abc-1&date=2026-10-28'), '#/failures?flow=abc-1&date=2026-10-28', 'the failures deep link survives');
  assert.equal(sanitizeReturnTo('#/messages/a%20b'), '#/messages/a%20b', 'an ordinary encoded id survives');
  for (const bad of [
    '#//', '#//evil.com', '#///x', // a second leading slash
    '#/?x=1', '#/login', '#/LOGIN', '#/Login', '#/lOgIn/x', '#/login?a=1', '#/%6c%6fgin', '#/%6C%6Fgin?x=1', '#/loginx', // sign-in state in any spelling
    '#/a%00b', '#/%0d%0aX', '#/%0A', '#/a%5cevil', '#/..%2f..%2f%00', // encoded NUL, CR, LF, backslash
    '#/a\u0000b', '#/a\nb', '#/a\r\nb', '#/\n', '#/a\n', '#/\u202e', // raw NUL, newline, bidi override
    '#/a#b', '#/"x', '#/<', '#/%E0%A4%A' // not a clean route
  ]) {
    assert.equal(sanitizeReturnTo(bad), null, JSON.stringify(bad));
  }
  // the only thing an accepted value can do is move the fragment: it always starts with #/ and never with #//
  for (const ok of ['#/javascript:alert(1)', "#/a'b", '#/..%2f..%2f', '#/%2e%2e/']) {
    const kept = sanitizeReturnTo(ok);
    assert.ok(kept === null || (kept.startsWith('#/') && !kept.startsWith('#//')), ok);
  }
});

test('landing after login moves only from the bare console address', () => {
  assert.equal(landingAfterLogin('', '#/versions'), '#/versions');
  assert.equal(landingAfterLogin('#/', '#/versions'), '#/versions');
  assert.equal(landingAfterLogin('#/flows', '#/versions'), null, 'an address the visitor already chose wins');
  assert.equal(landingAfterLogin('', 'https://evil.example/'), null);
  assert.equal(landingAfterLogin('', null), null);
});

test('storage round trip: attempt, logged-out flag and return-to; blocked storage never throws', () => {
  globalThis.sessionStorage = fakeStorage();
  flow.markAttempt(NOW);
  flow.rememberReturnTo('#/failures');
  assert.deepEqual(flow.readLoginMemory(NOW + 1), { attemptAt: NOW, loggedOut: false, storageOk: true, now: NOW + 1 });
  flow.clearAttempt();
  assert.equal(flow.readLoginMemory(NOW).attemptAt, null);
  assert.equal(flow.takeReturnTo(), '#/failures');
  assert.equal(flow.takeReturnTo(), null, 'one use');
  flow.rememberReturnTo('#/failures');
  flow.markLoggedOut();
  assert.equal(flow.readLoginMemory(NOW).loggedOut, true);
  assert.equal(flow.takeReturnTo(), null, 'a sign-out forgets the screen');
  flow.clearLoggedOut();
  assert.equal(flow.readLoginMemory(NOW).loggedOut, false);

  globalThis.sessionStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  assert.equal(flow.readLoginMemory(NOW).storageOk, false);
  assert.doesNotThrow(() => { flow.markAttempt(); flow.clearAttempt(); flow.markLoggedOut(); flow.rememberReturnTo('#/x'); });
  assert.equal(flow.takeReturnTo(), null);
  delete globalThis.sessionStorage;
});

test('storage probe (row 571 follow-up 3): a storage that reads but cannot write is "no storage" - the loop guard needs a write', () => {
  globalThis.sessionStorage = { getItem: () => null, setItem() { throw new Error('QuotaExceededError'); }, removeItem() {} };
  assert.equal(flow.readLoginMemory(NOW).storageOk, false, 'setItem throws -> not usable');
  assert.equal(decideLoginView({ state: 'signed-out', ...flow.readLoginMemory(NOW) }), 'prompt', 'a button decides, never a redirect');
  globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() { throw new Error('blocked'); } };
  assert.equal(flow.readLoginMemory(NOW).storageOk, false, 'removeItem throws -> not usable');
  const ok = fakeStorage();
  globalThis.sessionStorage = ok;
  assert.equal(flow.readLoginMemory(NOW).storageOk, true);
  assert.equal(ok.map.size, 0, 'the probe leaves nothing behind');
  delete globalThis.sessionStorage;
});

test('restored page (row 571 follow-up 2): a bfcache restore is "failed" whatever the attempt age', () => {
  const stale = memory({ attemptAt: NOW - ATTEMPT_TTL_MS - 1, restored: true });
  assert.equal(decideLoginView({ state: 'signed-out', ...stale }), 'failed');
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ restored: true }) }), 'failed', 'even with no attempt on record');
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ restored: true, loggedOut: true }) }), 'logged-out');
  assert.equal(decideLoginView({ state: 'signed-out', ...memory({ restored: true, storageOk: false }) }), 'prompt');
  assert.equal(decideLoginView({ state: 'checking', ...memory({ restored: true }) }), 'wait');
});

// ---------- the Login component through the harness ----------
function stubWindow() {
  const listeners = {};
  const assigned = [];
  globalThis.window = {
    location: { assign: (u) => assigned.push(typeof u === 'function' ? u() : u), hash: '#/login' },
    addEventListener: (type, fn) => { listeners[type] = fn; },
    removeEventListener: (type) => { delete listeners[type]; }
  };
  return { listeners, assigned };
}
const login = (state, onRetry = () => {}) => mount('pages/Login.tsx', 'Login', { state, onRetry }, { LOGIN_ENTRY: () => '/login' }, ['../api/client']);

test('Login, signed out with no earlier attempt: goes straight to the provider once, no SSO button', async () => {
  const { assigned } = stubWindow();
  const storage = (globalThis.sessionStorage = fakeStorage());
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, ['/login'], 'one jump to LOGIN_ENTRY');
  assert.ok(storage.map.has('ip.loginAttempt'), 'the attempt is recorded before the jump');
  assert.equal(by(screen, 'data-action', 'sso').length, 0, 'no Continue with SSO button');
  assert.match(textOf(screen.tree()), /Redirecting to sign-in/);
  screen.unmount();
  delete globalThis.sessionStorage;
});

test('Login loop guard: back from the provider with no session shows Try again and does NOT redirect', async () => {
  const { assigned } = stubWindow();
  globalThis.sessionStorage = fakeStorage({ 'ip.loginAttempt': String(Date.now() - 2_000) });
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, [], 'no automatic second redirect');
  assert.match(textOf(screen.tree()), /did not complete/);
  const retry = by(screen, 'data-action', 'sso')[0];
  assert.ok(retry, 'a Try again button');
  assert.equal(textOf(retry), 'Try again');
  retry.props.onClick();
  await screen.settle();
  assert.deepEqual(assigned, ['/login'], 'the button, and only the button, starts the next attempt');
  retry.props.onClick();
  assert.deepEqual(assigned, ['/login'], 'a double click does not jump twice');
  screen.unmount();
  delete globalThis.sessionStorage;
});

test('Login after sign-out: landing with a Sign in button, no automatic redirect', async () => {
  const { assigned } = stubWindow();
  const storage = (globalThis.sessionStorage = fakeStorage({ 'ip.loggedOut': '1' }));
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, []);
  assert.match(textOf(screen.tree()), /signed out/);
  const button = by(screen, 'data-action', 'sso')[0];
  assert.equal(textOf(button), 'Sign in');
  assert.equal(storage.map.has('ip.loggedOut'), false, 'the flag is spent once shown');
  button.props.onClick();
  assert.deepEqual(assigned, ['/login']);
  screen.unmount();
  delete globalThis.sessionStorage;
});

test('Login without usable storage shows a Sign in button instead of redirecting', async () => {
  const { assigned } = stubWindow();
  delete globalThis.sessionStorage;
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, []);
  assert.equal(textOf(by(screen, 'data-action', 'sso')[0]), 'Sign in');
  screen.unmount();
});

test('Login while checking or after a failed check: no redirect; Retry is kept', async () => {
  const { assigned } = stubWindow();
  globalThis.sessionStorage = fakeStorage();
  const checking = await login('checking');
  await checking.settle();
  assert.deepEqual(assigned, []);
  assert.match(textOf(checking.tree()), /Checking your session/);
  checking.unmount();

  let retried = 0;
  const failed = await login('error', () => { retried += 1; });
  await failed.settle();
  assert.deepEqual(assigned, []);
  by(failed, 'data-action', 'retry-session')[0].props.onClick();
  assert.equal(retried, 1);
  failed.unmount();
  delete globalThis.sessionStorage;
});

test('Login: Back from the provider restored from the back/forward cache shows Try again, not a frozen redirect', async () => {
  const { assigned, listeners } = stubWindow();
  globalThis.sessionStorage = fakeStorage();
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, ['/login']);
  assert.equal(typeof listeners.pageshow, 'function');
  listeners.pageshow({ persisted: false });
  await screen.settle();
  assert.match(textOf(screen.tree()), /Redirecting/, 'an ordinary load keeps the redirect view');
  listeners.pageshow({ persisted: true });
  await screen.settle();
  assert.match(textOf(screen.tree()), /did not complete/);
  assert.equal(textOf(by(screen, 'data-action', 'sso')[0]), 'Try again');
  assert.deepEqual(assigned, ['/login'], 'and no second redirect');
  screen.unmount();
  assert.equal(listeners.pageshow, undefined);
  delete globalThis.sessionStorage;
});

test('Login: a page restored from the bfcache after the attempt went stale shows Try again, never a hanging "Redirecting"', async () => {
  const { assigned, listeners } = stubWindow();
  const storage = (globalThis.sessionStorage = fakeStorage());
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, ['/login']);
  storage.map.set('ip.loginAttempt', String(Date.now() - ATTEMPT_TTL_MS - 60_000)); // the stamp aged out while the page sat in the cache
  listeners.pageshow({ persisted: true });
  await screen.settle();
  assert.match(textOf(screen.tree()), /did not complete/);
  assert.doesNotMatch(textOf(screen.tree()), /Redirecting/);
  const retry = by(screen, 'data-action', 'sso')[0];
  assert.equal(textOf(retry), 'Try again');
  retry.props.onClick();
  assert.deepEqual(assigned, ['/login', '/login'], 'Try again starts the next attempt');
  screen.unmount();
  delete globalThis.sessionStorage;
});

test('Login: a storage that cannot be written shows the Sign in button, so Back is never a trap', async () => {
  const { assigned } = stubWindow();
  globalThis.sessionStorage = { getItem: () => null, setItem() { throw new Error('QuotaExceededError'); }, removeItem() {} };
  const screen = await login('signed-out');
  await screen.settle();
  assert.deepEqual(assigned, [], 'no automatic redirect without a working guard');
  assert.equal(textOf(by(screen, 'data-action', 'sso')[0]), 'Sign in');
  screen.unmount();
  delete globalThis.sessionStorage;
});

// ---------- wiring the rest of the console ----------
test('the SSO page copy is gone from the source', () => {
  const page = readFileSync(new URL('../../src/pages/Login.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(page, /Continue with SSO/);
  assert.doesNotMatch(page, /You will be redirected/);
});

test('App wires sign-out to the logged-out flag and a live session to the return-to restore', () => {
  const app = readFileSync(new URL('../../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /markLoggedOut\(\)/, 'logout marks the landing');
  assert.match(app, /clearAttempt\(\)/, 'a live session clears the attempt');
  assert.match(app, /landingAfterLogin\(/, 'return-to is restored');
  assert.match(app, /rememberReturnTo\(/, 'return-to is remembered before the sign-in state');
});

test('a 401 remembers the screen the visitor asked for before moving to #/login', async () => {
  const nav = { hash: '#/versions' };
  globalThis.window = { location: { get hash() { return nav.hash; }, set hash(v) { nav.hash = v; }, assign() {} } };
  globalThis.document = { cookie: '' };
  globalThis.fetch = async () => new Response('{}', { status: 401 });
  const storage = (globalThis.sessionStorage = fakeStorage());
  const { consoleFetch } = await import('../../src/api/client.ts');
  await consoleFetch('/api/v1/admin/flows');
  assert.equal(nav.hash, '#/login');
  assert.equal(storage.map.get('ip.returnTo'), '#/versions');
  await consoleFetch('/api/v1/me'); // already on the sign-in state: the remembered screen is not overwritten
  assert.equal(storage.map.get('ip.returnTo'), '#/versions');
  delete globalThis.sessionStorage;
});

// ---------- brand (row 571 follow-up 1): kale-design is not used on this project (overview, Dipesh 2026-10-22) ----------
test('the sign-in card uses the project neutral tokens: no Deep Sea / Noon / Evening Sky colours, no kale wording', () => {
  const files = [
    '../../src/tokens.css',
    '../../src/pages/dashboard.css',
    '../../../compose/keycloak/themes/platform/login/resources/css/tokens.css',
    '../../../compose/keycloak/themes/platform/login/resources/css/styles.css'
  ];
  for (const file of files) {
    const css = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(css, /#081f4b|#eb4a26|#76cbff/i, `${file}: brand colour`);
    assert.doesNotMatch(css, /kale|deep sea|\bnoon\b|evening sky/i, `${file}: brand wording`);
  }
  const tokens = readFileSync(new URL('../../src/tokens.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.match(tokens, /--color-rule-top:\s*var\(--color-accent\)/, 'top rule = the accent');
  assert.match(tokens, /--color-rule-bottom:\s*var\(--color-border\)/, 'foot rule = the hairline');
});
