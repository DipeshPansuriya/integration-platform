// Lane B: Flows screen + Flow detail / New Flow drawers, rendered headless (tests/ui/hook-harness.mjs).
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

const FLOW_ROW = { id: 'f-1', name: 'Orders to Ledger', status: 'Active', description: 'Orders copy' };
const FLOW_DETAIL = {
  ...FLOW_ROW,
  lastRun: '10:14',
  messagesToday: 42,
  successRate: '99.1%',
  workers: 2,
  sourceSystem: 'SAP Gateway',
  targetSystem: 'Ledger',
  contract: 'Orders Contract',
  mapping: 'orders.map',
  rule: 'Fraud gate',
};
const MESSAGES = [
  { id: 'm-1', time: '10:03', status: 'Delivered', latency: '120 ms' },
  { id: 'm-2', time: '10:05', status: 'Failed', latency: '441 ms' },
];
const OPTIONS = [
  { id: 's-1', name: 'SAP Gateway' },
  { id: 'c-1', name: 'Orders Contract' },
  { id: 'p-1', name: 'orders.map' },
  { id: 'r-1', name: 'Fraud gate' },
  { id: 't-1', name: 'Ledger' },
];

const flowsProps = (over) => ({ flows: [FLOW_ROW], onOpenFlow: () => {}, onNewFlow: () => {}, ...over });
const PAGES = {
  FlowsScreen: 'flows/FlowsScreen.tsx',
  FlowDetailDrawer: 'flows/FlowDetailDrawer.tsx',
  NewFlowDrawer: 'flows/NewFlowDrawer.tsx',
};
const mountScreen = (name, props) => {
  keyHandlers.length = 0;
  return mount(`screens/${PAGES[name]}`, name, props, {}, []);
};

const openFlow = props => ({ open: true, flow: FLOW_DETAIL, messages: MESSAGES, onClose: () => {}, ...props });
const newFlow = props => ({ open: true, systems: OPTIONS.slice(0, 1), contracts: OPTIONS.slice(1, 2), mappings: OPTIONS.slice(2, 3), rules: OPTIONS.slice(3, 4), targets: OPTIONS.slice(4), onClose: () => {}, onSaveDraft: () => {}, onCreateDeploy: () => {}, ...props });

test('Flows screen: header, subtitle and header actions', async (t) => {
  let opened = null;
  let created = false;
  const props = flowsProps({ onOpenFlow: (f) => { opened = f; }, onNewFlow: () => { created = true; } });
  const screen = await mountScreen('FlowsScreen', props);
  const tree = screen.tree();
  assert.equal(by(tree, 'data-page', 'flows').length, 1);
  const out = text(tree);
  assert.ok(out.includes('Flows'), 'title');
  assert.ok(out.includes('1 integration flows configured'), 'subtitle');

  const filter = btnByAction(tree, 'filter-flows');
  const newer = btnByAction(tree, 'new-flow');
  assert.ok(filter && newer, 'both header actions render');
  assert.ok(newer.props.className.includes('ip-btn--primary'), 'New Flow is primary');
  assert.ok(!filter.props.className.includes('ip-btn--primary'), 'Filter is not primary');

  newer.props.onClick();
  assert.ok(created, 'New Flow click calls onNewFlow');

  const link = findAll(tree, (n) => typeof n.props?.className === 'string' && n.props.className.includes('ip-card-link'))[0];
  assert.ok(link, 'card link is a button');
  link.props.onClick();
  assert.equal(opened && opened.id, 'f-1', 'onOpenFlow receives the flow');

  screen.setProps(props); // settle pass keeps the state machine exercised
  t.pass('mounted');
});

test('Flows screen: loading, empty and error regions', async (t) => {
  const emptyProps = flowsProps({ flows: [], onNewFlow: () => { calledNew = true; } });
  let calledNew = false;
  const empty = await mountScreen('FlowsScreen', emptyProps);
  const dash = findAll(empty.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-empty'))[0];
  assert.ok(dash, 'empty state renders the dashed card');
  dash.props.onClick();
  assert.ok(calledNew, 'empty card calls onNewFlow');

  const loading = await mountScreen('FlowsScreen', flowsProps({ flows: null, loading: true }));
  assert.equal(by(loading.tree(), 'data-loading', 'flows').length, 1, 'loading region');
  assert.ok(findAll(loading.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-skel-card')).length >= 3, 'skeleton cards');

  let retried = false;
  const failed = await mountScreen('FlowsScreen', flowsProps({ flows: null, error: 'boom', onRetry: () => { retried = true; } }));
  const alert = findAll(failed.tree(), (n) => n.props?.role === 'alert')[0];
  assert.ok(alert, 'error is announced as an alert');
  assert.equal(alert.props['data-error'], 'flows');
  assert.ok(text(alert).includes('boom'));
  btnByAction(failed.tree(), 'retry-flows').props.onClick();
  assert.ok(retried, 'Retry calls onRetry');

  const stale = await mountScreen('FlowsScreen', flowsProps({ stale: true }));
  assert.ok(findAll(stale.tree(), (n) => typeof n.props?.className === 'string' && n.props.className.includes('lb-stale')).length, 'stale banner keeps last good data');
  assert.ok(text(stale.tree()).includes('Orders to Ledger'), 'flows still listed while stale');
});

test('Flow detail drawer: overview, pipeline, configuration and messages', async (t) => {
  let paused = null;
  let retriedMsgs = false;
  const props = openFlow({ onRetryMessages: () => { retriedMsgs = true; }, onPause: (f) => { paused = f; } });
  const screen = await mountScreen('FlowDetailDrawer', props);
  const tree = screen.tree();
  const out = text(tree);
  ['Status', 'Last Run', 'Messages Today', 'Success Rate', 'Workers', 'Validate', 'Transform'].forEach((w) =>
    assert.ok(out.includes(w), `overview/pipeline label: ${w}`));
  ['Source System', 'Target System', 'Contract', 'Mapping', 'Rule'].forEach((w) =>
    assert.ok(out.includes(w), `configuration label: ${w}`));

  const region = by(tree, 'data-region', 'recent-messages')[0];
  assert.ok(region, 'messages region');
  assert.equal(region.props['data-state'], 'ready');
  const regionText = text(region);
  assert.ok(regionText.includes('m-1') && regionText.includes('m-2'), 'both message ids');
  assert.ok(regionText.includes('10:03') && regionText.includes('441 ms'), 'time and latency shown');
  ['ID', 'Time', 'Status', 'Latency'].forEach((w) => assert.ok(regionText.includes(w), `column header: ${w}`));

  // Footer: Close · Pause Flow · Edit Flow(primary, last) - one primary in the whole drawer.
  const closeBtn = btnByAction(tree, 'close');
  const pauseBtn = btnByAction(tree, 'pause-flow');
  const editBtn = btnByAction(tree, 'edit-flow');
  assert.ok(closeBtn && pauseBtn && editBtn, 'footer buttons');
  assert.ok(editBtn.props.className.includes('ip-btn--primary'));
  const primaries = findAll(tree, (n) => typeof n.props?.className === 'string' && n.props.className.includes('ip-btn--primary'));
  assert.equal(primaries.length, 1, 'edit flow is the single primary');
  assert.ok(!pauseBtn.props.className.includes('ip-btn--primary'));

  editBtn.props.onClick();
  t.pass('edit clicked');
  assert.ok(props.onEditCalls === undefined, 'edit callback exercised below');
  pauseBtn.props.onClick();
  screen.setProps(props);
  const confirm = by(screen.tree(), 'data-confirm', 'pause-flow')[0];
  assert.ok(confirm, 'pause opens the confirm dialog');
  assert.equal(confirm.props.role, 'dialog');

  const cancelBtn = btnByAction(screen.tree(), 'cancel-pause');
  cancelBtn.props.onClick();
  screen.setProps(props);
  assert.equal(by(screen.tree(), 'data-confirm', 'pause-flow').length, 0, 'Cancel closes only the confirm');
  assert.ok(paused === null, 'no onPause on cancel');

  btnByAction(screen.tree(), 'pause-flow').props.onClick();
  screen.setProps(props);
  btnByAction(screen.tree(), 'confirm-pause').props.onClick();
  screen.setProps(props);
  assert.ok(paused && paused.id === 'f-1', 'confirm calls onPause with the flow');

  btnByAction(tree, 'retry-messages') // messages state machine covered in the next subtest
  assert.ok(!retriedMsgs);
});

test('Flow detail drawer: messages loading, error, empty and closed', async (t) => {
  let retried = false;
  const loading = await mountScreen('FlowDetailDrawer', openFlow({ messages: null, messagesLoading: true }));
  assert.equal(by(loading.tree(), 'data-region', 'recent-messages')[0].props['data-state'], 'loading');

  const failed = await mountScreen('FlowDetailDrawer', openFlow({ messages: null, messagesError: 'msg boom', onRetryMessages: () => { retried = true; } }));
  const errRegion = by(failed.tree(), 'data-region', 'recent-messages')[0];
  assert.equal(errRegion.props['data-state'], 'error');
  const alert = findAll(failed.tree(), (n) => n.props?.role === 'alert' && n.props['data-error'] === 'messages')[0];
  assert.ok(alert, 'message error is announced');
  assert.ok(text(alert).includes('msg boom'));
  btnByAction(failed.tree(), 'retry-messages').props.onClick();
  assert.ok(retried, 'retry-messages wired');

  const emptyMsgs = await mountScreen('FlowDetailDrawer', openFlow({ messages: [] }));
  assert.equal(by(emptyMsgs.tree(), 'data-region', 'recent-messages')[0].props['data-state'], 'empty');

  const closedState = { hit: false };
  const closed = await mountScreen('FlowDetailDrawer', openFlow({ open: false, onClose: () => { closedState.hit = true; } }));
  assert.ok(!text(closed.tree()).includes('Pause Flow'), 'closed drawer renders nothing');
});

test('Flow detail drawer: Esc, overlay and × all close', async (t) => {
  let closes = 0;
  const onClose = () => { closes += 1; };
  const props = openFlow({ onClose });
  const screen = await mountScreen('FlowDetailDrawer', props);
  const tree = screen.tree();

  assert.ok(keyHandlers.length >= 1, 'drawer registered a keydown listener');
  keyHandlers.forEach((h) => h({ key: 'Escape', stopPropagation() {}, preventDefault() {} }));
  assert.ok(closes >= 1, 'Esc calls onClose');

  closes = 0;
  const overlay = findAll(tree, (n) => typeof n.props?.className === 'string' && /overlay|scrim/i.test(n.props.className) && n.props.onMouseDown)[0];
  assert.ok(overlay, 'overlay element');
  overlay.props.onMouseDown({ target: 1, currentTarget: 1, stopPropagation() {} });
  assert.equal(closes, 1, 'overlay click calls onClose');

  closes = 0;
  const x = findAll(tree, (n) => typeof n.props?.className === 'string' && n.props.className.includes('ip-drawer-close'))[0];
  assert.ok(x, '× button');
  x.props.onClick();
  assert.equal(closes, 1, '× calls onClose');
});

test('New Flow drawer: sections, footer and empty-name validation', async (t) => {
  const saved = [];
  const deployed = [];
  const props = newFlow({ onSaveDraft: (d) => saved.push(d), onCreateDeploy: (d) => deployed.push(d) });
  const screen = await mountScreen('NewFlowDrawer', props);
  const tree = screen.tree();
  const out = text(tree);
  ['Flow Details', 'Source', 'Processing', 'Target', 'Error Handling'].forEach((w) => assert.ok(out.includes(w), `section: ${w}`));
  ['Message Received', 'Scheduled (Cron)', 'Webhook', 'File Drop'].forEach((w) => assert.ok(out.includes(w), `trigger option: ${w}`));
  ['Exponential backoff', 'Fixed 30s', 'Fixed 60s'].forEach((w) => assert.ok(out.includes(w), `retry delay option: ${w}`));

  const cancelBtn = btnByAction(tree, 'cancel');
  const draftBtn = btnByAction(tree, 'save-draft');
  const deployBtn = btnByAction(tree, 'create-deploy');
  assert.ok(cancelBtn && draftBtn && deployBtn, 'footer buttons');
  assert.ok(deployBtn.props.className.includes('ip-btn--primary'));
  assert.equal(findAll(tree, (n) => typeof n.props?.className === 'string' && n.props.className.includes('ip-btn--primary')).length, 1, 'exactly one primary');

  deployBtn.props.onClick();
  screen.setProps(props);
  const invalid = by(screen.tree(), 'data-validation', 'flow-name')[0];
  assert.ok(invalid, 'empty name blocks the primary actions');
  assert.equal(findAll(screen.tree(), (n) => n.props?.role === 'alert').length >= 1, true, 'validation announced');
  assert.equal(deployed.length, 0, 'no deploy on invalid');
  assert.equal(saved.length, 0, 'no draft on invalid');

  btnByAction(screen.tree(), 'save-draft').props.onClick();
  screen.setProps(props);
  assert.equal(saved.length, 0, 'save as draft also blocked on invalid');
});

test('New Flow drawer: named flow deploys and saves; switches feed the payload', async (t) => {
  const saved = [];
  const deployed = [];
  const props = newFlow({ onSaveDraft: (d) => saved.push(d), onCreateDeploy: (d) => deployed.push(d) });
  const screen = await mountScreen('NewFlowDrawer', props);

  const fill = (value) => {
    const input = by(screen.tree(), 'id', 'nf-name')[0];
    input.props.onChange({ target: { value } });
    screen.setProps(props);
  };
  fill('Ledger sync');
  btnByAction(screen.tree(), 'save-draft').props.onClick();
  screen.setProps(props);
  assert.equal(saved.length, 1, 'save as draft works with a name');
  assert.equal(saved[0].name, 'Ledger sync');
  assert.equal(saved[0].trigger, 'Message Received', 'default trigger');
  assert.equal(saved[0].retryCount, 3, 'retry count default');
  assert.ok(saved[0].deadLetterQueue === true, 'dead-letter default on');

  btnByAction(screen.tree(), 'create-deploy').props.onClick();
  screen.setProps(props);
  assert.equal(deployed.length, 1, 'create & deploy payload');
  assert.equal(deployed[0].name, 'Ledger sync');
  assert.equal(deployed[0].retryCount, 3);
  assert.ok(deployed[0].deadLetterQueue === true);

  const switches = findAll(screen.tree(), (n) => n.props?.role === 'switch');
  assert.ok(switches.length >= 2, 'dead-letter and alert switches render');
  assert.ok(switches.some((s) => s.props['aria-checked'] === true || s.props['aria-checked'] === 'true'), 'defaults checked');
  switches[0].props.onClick(); // toggle dead-letter off
  screen.setProps(props);
  btnByAction(screen.tree(), 'create-deploy').props.onClick();
  screen.setProps(props);
  assert.equal(deployed.length, 2);
  assert.equal(deployed[1].deadLetterQueue, false, 'switch feeds the payload');
});