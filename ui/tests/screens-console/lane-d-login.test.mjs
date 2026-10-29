import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from '../ui/hook-harness.mjs';

const by = (screen, name, value) =>
  findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));

const byClass = (screen, cls) => findAll(screen.tree(), (n) => String(n.props?.className || '').split(' ').includes(cls));

const textOf = (node) =>
  Array.isArray(node) ? node.map(textOf).join('') : typeof node === 'object' && node ? textOf(node.props?.children) : String(node ?? '');

const page = (props = {}) => mount('screens/login/LoginScreen.tsx', 'LoginScreen', { state: 'signed-out', ...props }, {}, []);

const T = (nodes) => Promise.all(nodes.map(async (n) => n));

test('login renders existing page/region data attributes in every view', async () => {
  for (const view of ['wait', 'redirect', 'failed', 'logged-out', 'prompt', 'session-error']) {
    const s = await page({ state: 'signed-out', view });
    assert.equal(by(s, 'data-page', 'login').length, 1, view);
    assert.equal(by(s, 'data-session-state', 'signed-out').length, 1, view);
    assert.equal(by(s, 'data-login-view', view).length, 1, `view=${view}`);
    assert.equal(by(s, 'class', 'signin').length + byClass(s, 'signin').length >= 1, true, 'signin main present');
    s.unmount();
  }
  const checking = await page({ state: 'checking' });
  assert.equal(by(checking, 'data-session-state', 'checking').length, 1);
  assert.equal(by(checking, 'data-login-view', 'wait').length, 1, 'checking derives to wait view');
  checking.unmount();
  const signedOut = await page({});
  assert.equal(by(signedOut, 'data-login-view', 'prompt').length, 1, 'signed-out derives to prompt view');
  signedOut.unmount();
  const errored = await page({ state: 'error' });
  assert.equal(by(errored, 'data-login-view', 'session-error').length, 1, 'error derives to session-error view');
  errored.unmount();
});

test('login prompt: sso click routed, lede copy exact', async () => {
  let sso = 0;
  const s = await page({ onSSO: () => { sso += 1; } });
  const btn = by(s, 'data-action', 'sso');
  assert.equal(btn.length, 1);
  btn[0].props.onClick();
  assert.equal(sso, 1);
  const t = textOf(s.tree());
  assert.equal(t.includes("Sign in to your tenant's integration workbench."), true, 'prompt lede');
  assert.equal(t.includes('Sign in'), true, 'button label');
  s.unmount();
});

test('login failed view: try-again copy, heading, click routes', async () => {
  let sso = 0;
  const s = await page({ view: 'failed', onSSO: () => { sso += 1; } });
  const tryAgain = by(s, 'data-action', 'sso');
  assert.equal(tryAgain.length, 1);
  tryAgain[0].props.onClick();
  assert.equal(sso, 1);
  const t = textOf(s.tree());
  assert.equal(t.includes('Sign-in did not complete'), true, 'failed heading');
  assert.equal(t.includes('Try again'), true, 'try-again label');
  assert.equal(t.includes('The sign-in was cancelled or did not finish, so nothing was opened.'), true, 'failed lede');
  s.unmount();
});

test('login logged-out view lede and heading', async () => {
  const s = await page({ view: 'logged-out' });
  const t = textOf(s.tree());
  assert.equal(t.includes('You have been signed out'), true, 'logged-out heading');
  assert.equal(t.includes('Your session has ended. Sign in again to continue.'), true, 'logged-out lede');
  s.unmount();
});

test('login wait/redirect: status ledes are role=status aria-live=polite with copy', async () => {
  const waitT = await (async () => {
    const s = await page({ view: 'wait' });
    const ledes = by(s, 'role', 'status');
    assert.equal(ledes.length, 1);
    assert.equal(ledes[0].props['aria-live'], 'polite');
    const t = textOf(s.tree());
    s.unmount();
    return t;
  })();
  assert.equal(waitT.includes('Checking your session...'), true, 'wait lede');
  const s2 = await page({ view: 'redirect' });
  assert.equal(textOf(s2.tree()).includes('Redirecting to sign-in...'), true, 'redirect lede');
  s2.unmount();
});

test('login session-error: retry action routed and alert region present', async () => {
  let retried = 0;
  const s = await page({ view: 'session-error', onRetry: () => { retried += 1; } });
  const retry = by(s, 'data-action', 'retry-session');
  assert.equal(retry.length, 1);
  retry[0].props.onClick();
  assert.equal(retried, 1);
  const alert = by(s, 'role', 'alert');
  assert.equal(alert.length, 1, 'role=alert region for session error');
  const t = textOf(s.tree());
  assert.equal(t.includes('We could not confirm who you are.'), true, 'session-error copy');
  assert.equal(t.includes('Retry'), true, 'Retry label');
  s.unmount();
});

test('login hero: headline, brand and four feature blocks', async () => {
  const s = await page({});
  const t = textOf(s.tree());
  assert.equal(t.includes('Streamline your'), true, 'hero headline line 1');
  assert.equal(t.includes('cargo integrations'), true, 'hero headline line 2');
  assert.equal(t.includes('Connect systems, transform data, and route messages across your cargo handling ecosystem.'), true, 'hero sub');
  assert.equal(t.includes('Integration Platform'), true, 'brand');
  const feats = byClass(s, 'signin-feature');
  assert.equal(feats.length, 4, 'four signin-feature blocks');
  assert.equal(t.includes('Visual flow builder with drag-and-drop'), true);
  assert.equal(t.includes('Real-time delivery monitoring'), true);
  s.unmount();
});

test('login prompt view does not show failure copy', async () => {
  const s = await page({});
  const t = textOf(s.tree());
  assert.equal(t.includes('Try again'), false, 'not failure copy in prompt view');
  s.unmount();
});

test('login uses no DS Icon (harness-unsafe components absent)', async () => {
  const s = await page({});
  assert.equal(s.components('Icon').length, 0, 'Icon must never be rendered');
  s.unmount();
});