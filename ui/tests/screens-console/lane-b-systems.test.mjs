// Lane B: Systems screen + System detail / Register System drawers, rendered headless (tests/ui/hook-harness.mjs).
// Contract-level assertions only: data-* hooks, roles and visible wording - never DS internals.
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from '../ui/hook-harness.mjs';

// Drawer mounts a focus timer and document keydown listeners; neutralize both headlessly.
const keyHandlers = [];
globalThis.HTMLElement = globalThis.HTMLElement ?? class {};
globalThis.document = {
  activeElement: null,
  addEventListener: (_t, fn) => keyHandlers.push(fn),
  removeEventListener: () => {},
};
globalThis.setTimeout = () => 0;
globalThis.clearTimeout = () => {};

const text = (n) =>
  Array.isArray(n) ? n.map(text).join('')
    : typeof n === 'string' ? n
      : typeof n === 'number' ? String(n)
        : n && typeof n === 'object' ? text(n.props?.children) : '';
const where = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);
const by = (tree, name, value) => findAll(tree, where(name, value));
const btnByAction = (tree, action) => by(tree, 'data-action', action)[0];

const SYSTEM = {
  id: 'sys-1',
  name: 'SAP Gateway',
  code: 'SAP-GW',
  status: 'Healthy',
  protocol: 'REST API',
  latencyMs: 120,
  lastCheck: '10:05',
};
const DETAIL = { ...SYSTEM, auth: 'HMAC Signature', baseUrl: 'https://sap.example.com', timeoutMs: 30000, direction: 'Bidirectional' };
const KPIS = { avgLatency: '120 ms', uptime: '99.9%', requests: '1,204', errors: '3' };
const EVENTS = [{ time: '10:05', text: 'Health check passed', tone: 'ok' }];

const baseDraft = {
  name: 'Ledger Service',
  code: 'LEDGER',
  protocol: 'REST API',
  direction: 'Bidirectional',
  baseUrl: 'https://ledger.example.com',
  timeoutMs: 30000,
  retryLimit: 3,
  authMethod: 'HMAC Signature',
  healthMonitoring: true,
  healthEndpoint: '/health',
  healthInterval: 'Every 30 seconds',
  tags: ['core', 'ledger'],
};

const mountDrawer = (name, props) => {
  keyHandlers.length = 0;
  return mount(`screens/systems/${name}`, name, props, {}, []);
};
const detailProps = (over) => ({ open: true, system: DETAIL, kpis: KPIS, usedBy: ['Orders to Ledger'], events: EVENTS, onClose: () => {}, ...over });
const registerProps = (over) => ({ open: true, onClose: () => {}, onTestConnection: () => {}, onRegister: () => {}, testState: 'idle', testMessage: '', ...over });

test('Systems screen: header, subtitle, cards and actions', async (t) => {
  let opened = null;
  let registering = false;
  const props = { systems: [SYSTEM], onOpenSystem: (s) => { opened = s; }, onRegisterSystem: () => { registering = true; } };
  const screen = await mountDrawer('SystemsScreen', props);
  const tree = screen.tree();
  assert.equal(by(tree, 'data-page', 'systems').length, 1);
  const out = text(tree);
  assert.ok(out.includes('Systems'), 'title');
  assert.ok(out.includes('1 registered endpoints'), 'subtitle');
  const reg = btnByAction(tree, 'register-system');
  assert.ok(reg, 'Register System action renders');
  assert.ok(reg.props.className.includes('ip-btn--primary'), 'Register System is primary');

  const card = findAll(tree, (n) => (Array.isArray(n) ? false : n.type?.name === 'EntityCard' || (n.props && n.props.name === 'SAP Gateway' && n.props.onOpen)))[0];
  assert.ok(card, 'EntityCard rendered');
  if (card.props?.onOpen) {
    card.props.onOpen();
    assert.ok(opened && opened.id === 'sys-1', 'onOpenSystem receives the system');
  }

  const out2 = text(tree);
  assert.ok(out2.includes('120 ms') && out2.includes('10:05'), 'latency and last-check rows');
  reg.props.onClick();
  assert.ok(registering, 'Register System click calls onRegisterSystem');
});

test('Systems screen: loading, empty, error and stale', async (t) => {
  let called = false;
  const empty = await mountDrawer('SystemsScreen', { systems: [], onRegisterSystem: () => { called = true; } });
  const dash = findAll(empty.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-empty'))[0];
  assert.ok(dash, 'empty state renders');
  dash.props.onClick();
  assert.ok(called, 'empty card calls onRegisterSystem');

  const loading = await mountDrawer('SystemsScreen', { systems: null, loading: true });
  assert.equal(by(loading.tree(), 'data-loading', 'systems').length, 1, 'loading region');
  assert.ok(findAll(loading.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-skel-card')).length >= 3, 'skeleton cards');

  let retried = false;
  const failed = await mountDrawer('SystemsScreen', { systems: null, error: 'sys boom', onRetry: () => { retried = true; } });
  const alert = findAll(failed.tree(), (n) => n.props?.role === 'alert')[0];
  assert.ok(alert, 'error announced');
  assert.equal(alert.props['data-error'], 'systems');
  assert.ok(text(alert).includes('sys boom'));
  btnByAction(failed.tree(), 'retry-systems').props.onClick();
  assert.ok(retried, 'Retry calls onRetry');

  const stale = await mountDrawer('SystemsScreen', { systems: [SYSTEM], stale: true });
  assert.ok(findAll(stale.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-stale')).length, 'stale banner');
  assert.ok(text(stale.tree()).includes('SAP Gateway'), 'last good data kept');
});

test('System detail drawer: overview, KPIs, used-by flows and events', async (t) => {
  let tested = false;
  let edited = null;
  let retriedEvents = false;
  const props = detailProps({ onTestConnection: () => { tested = true; }, onEdit: (s) => { edited = s; }, onRetryEvents: () => { retriedEvents = true; } });
  const screen = await mountDrawer('SystemDetailDrawer', props);
  const tree = screen.tree();
  const out = text(tree);
  ['Protocol', 'Auth', 'Base URL', 'Timeout', 'Direction'].forEach((w) => assert.ok(out.includes(w), `overview label: ${w}`));
  assert.ok(out.includes('https://sap.example.com'), 'base url shown');
  assert.ok(out.includes('Avg Latency') && out.includes('Uptime') && out.includes('Requests') && out.includes('Errors'), 'KPI labels');

  const kpiBand = by(tree, 'data-kpis', 'ready')[0];
  assert.ok(kpiBand, 'KPI band ready');
  ['avg-latency', 'uptime', 'requests', 'errors'].forEach((k) => assert.equal(by(kpiBand.props.children, 'data-kpi', k).length >= 1 || kpiBand.props.children.some((c) => c && c.props && c.props['data-kpi'] === k), true, `kpi node: ${k}`));

  assert.ok(out.includes('Using by Flows') === false && out.includes('Used by Flows'), 'used by flows heading');
  assert.ok(out.includes('Orders to Ledger'), 'used-by tag');

  const region = by(tree, 'data-region', 'recent-events')[0];
  assert.ok(region, 'events region');
  assert.equal(region.props['data-state'], 'ready');
  assert.ok(text(region).includes('Health check passed'), 'event text');

  const closeBtn = btnByAction(tree, 'close');
  const testBtn = btnByAction(tree, 'test-connection');
  const editBtn = btnByAction(tree, 'edit-system');
  assert.ok(closeBtn && testBtn && editBtn, 'footer buttons');
  assert.ok(editBtn.props.className.includes('ip-btn--primary') && testBtn.props.className.includes('ip-btn--secondary'), 'footer variants');
  assert.ok(btnByAction(tree, 'close').props.className.includes('ip-btn--primary') === false, 'close is not primary');
  assert.equal(findAll(tree, (n) => typeof n.props?.className === 'string' && n.props.className.includes('ip-btn--primary')).length, 1, 'single primary');

  assert.equal(by(tree, 'data-field', 'status').length >= 1, true, 'status row');
  testBtn.props.onClick();
  assert.ok(tested, 'Test Connection wired');
});

test('System detail drawer: KPI loading, events machines and empty footer', async (t) => {
  let retried = false;
  const kpiLoading = await mountDrawer('SystemDetailDrawer', detailProps({ kpisLoading: true }));
  assert.equal(by(kpiLoading.tree(), 'data-kpis', 'loading').length, 1, 'kpi loading');

  const errRegionProps = detailProps({ events: null, eventsError: 'evt boom', onRetryEvents: () => { retried = true; } });
  const failed = await mountDrawer('SystemDetailDrawer', errRegionProps);
  const region = by(failed.tree(), 'data-region', 'recent-events')[0];
  assert.equal(region.props['data-state'], 'error');
  const alert = findAll(failed.tree(), (n) => n.props?.role === 'alert' && n.props['data-error'] === 'events')[0];
  assert.ok(alert, 'event error announced');
  assert.ok(text(alert).includes('evt boom'));
  btnByAction(failed.tree(), 'retry-events').props.onClick();
  assert.ok(retried, 'retry-events wired');

  const empty = await mountDrawer('SystemDetailDrawer', detailProps({ events: [] }));
  assert.equal(by(empty.tree(), 'data-region', 'recent-events')[0].props['data-state'], 'empty');

  const none = await mountDrawer('SystemDetailDrawer', detailProps({ system: null }));
  assert.ok(text(none.tree()).includes('No system selected.'), 'no system message');
  assert.ok(btnByAction(none.tree(), 'close'), 'close-only footer');
  assert.ok(!btnByAction(none.tree(), 'edit-system'), 'no edit without a system');
});

test('Register System drawer: sections, footer and submit validation', async (t) => {
  const tested = [];
  const registered = [];
  const props = registerProps({ onTestConnection: (d) => tested.push(d), onRegister: (d) => registered.push(d) });
  const screen = await mountDrawer('RegisterSystemDrawer', props);
  const tree = screen.tree();
  const out = text(tree);
  ['Basic Information', 'Connection', 'Authentication', 'Health Check', 'Tags'].forEach((w) => assert.ok(out.includes(w), `section: ${w}`));

  const cancelBtn = btnByAction(tree, 'cancel');
  const testBtn = btnByAction(tree, 'test-connection');
  const regBtn = btnByAction(tree, 'register');
  assert.ok(cancelBtn && testBtn && regBtn, 'footer buttons');
  assert.ok(regBtn.props.className.includes('ip-btn--primary'), 'Register is primary');
  assert.ok(testBtn.props.className.includes('ip-btn--secondary'), 'Test Connection is secondary');
  assert.ok(!cancelBtn.props.className.includes('ip-btn--primary'));

  regBtn.props.onClick();
  screen.setProps(props);
  assert.ok(by(screen.tree(), 'data-validation', 'register-system')[0], 'validation on register');

  testBtn.props.onClick();
  screen.setProps(props);
  assert.equal(registered.length, 0, 'no register on invalid');
  assert.equal(tested.length, 0, 'no test on invalid');
});

test('Register System drawer: auth fields swap by method', async (t) => {
  const cases = [
    ['HMAC Signature', ['client-id', 'client-secret']],
    ['OAuth 2.0', ['client-id', 'client-secret']],
    ['API Key', ['api-key']],
    ['Certificate (mTLS)', ['certificate']],
    ['Basic Auth', ['username', 'password']],
  ];
  for (const [method, expected] of cases) {
    const props = registerProps({ draft: { ...baseDraft, authMethod: method } });
    const screen = await mountDrawer('RegisterSystemDrawer', props);
    const fields = by(screen.tree(), 'data-auth-field').map((n) => n.props['data-auth-field']).sort();
    assert.deepEqual(fields, [...expected].sort(), `auth fields for ${method}`);
    screen.unmount();
  }

  // Method change via the select also swaps the fields without a remount.
  let tested = false;
  const props = registerProps({ draft: baseDraft, onTestConnection: () => { tested = true; } });
  const screen = await mountDrawer('RegisterSystemDrawer', props);
  const select = by(screen.tree(), 'id', 'rs-auth-method')[0];
  select.props.onChange({ target: { value: 'API Key' } });
  screen.setProps(props);
  const fields = by(screen.tree(), 'data-auth-field').map((n) => n.props['data-auth-field']).sort();
  assert.deepEqual(fields, ['api-key'], 'fields after method change');
});

test('Register System drawer: valid draft registers; defaults carried in the payload', async (t) => {
  const registered = [];
  let testedCalled = false;
  const props = registerProps({ draft: baseDraft, onRegister: (d) => registered.push(d), onTestConnection: () => { testedCalled = true; } });
  const screen = await mountDrawer('RegisterSystemDrawer', props);
  btnByAction(screen.tree(), 'register').props.onClick();
  screen.setProps(props);
  assert.equal(registered.length, 1, 'register with valid draft');
  const d = registered[0];
  assert.equal(d.timeoutMs, 30000, 'timeout default');
  assert.equal(d.retryLimit, 3, 'retry default');
  assert.equal(d.healthMonitoring, true, 'health monitoring default');
  assert.equal(d.healthInterval, 'Every 30 seconds', 'interval default');
  assert.equal(d.protocol, 'REST API', 'protocol default');
  assert.equal(d.direction, 'Bidirectional', 'direction default');
  assert.deepEqual(d.tags, ['core', 'ledger'], 'tags parsed');
  assert.equal(by(screen.tree(), 'data-validation', 'register-system').length, 0, 'no validation shown');

  btnByAction(screen.tree(), 'test-connection').props.onClick();
  screen.setProps(props);
  assert.ok(testedCalled, 'test connection works when valid');
});

test('Register System drawer: secrets are write-only, seeded values never echoed', async (t) => {
  const props = registerProps({
    draft: {
      ...baseDraft,
      authMethod: 'Basic Auth',
      authClientSecret: 'stored-secret-xyz',
      authApiKey: 'stored-key',
      authPassword: 'stored-pass',
    },
  });
  const screen = await mountDrawer('RegisterSystemDrawer', props);
  const tree = screen.tree();
  const out = text(tree);
  for (const secret of ['stored-secret-xyz', 'stored-key', 'stored-pass']) {
    assert.ok(!out.includes(secret), `secret ${secret.slice(0, 7)}… never rendered as text`);
  }
  const passwords = findAll(tree, (n) => n.props?.type === 'password').map((n) => n.props);
  assert.ok(passwords.length >= 1, 'password inputs render');
  passwords.forEach((p) => {
    assert.equal(p.value, '', 'password input starts empty');
  });
});

test('Register System drawer: connection test states', async (t) => {
  for (const [state, message] of [
    ['testing', ''],
    ['ok', 'Endpoint reachable in 120 ms'],
    ['fail', '401 unauthorized'],
  ]) {
    const props = registerProps({ testState: state, testMessage: message });
    const screen = await mountDrawer('RegisterSystemDrawer', props);
    const node = by(screen.tree(), 'data-testid', 'connection-test')[0];
    assert.ok(node, `test state node for ${state}`);
    assert.equal(node.props['data-state'], state);
    if (state === 'fail') assert.equal(node.props.role, 'alert', 'failure announced');
    if (state !== 'testing') assert.ok(text(node).includes(message), 'test message echoed');
    screen.unmount();
  }
});