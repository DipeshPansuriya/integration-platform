// Row 372 review M3 / M4: the shared Drawer's discard prompt keeps keyboard focus and the form behind it
// is ignored while it is open (M3); "dirty" is cleared by the caller's SUCCESS (useDrawerSaved), never by
// the submit event, so a refused submit does not lose the "Discard changes?" guard (M4).
// Rendered under the hook harness (no DOM): refs are faked, handlers are called from the element tree.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-drawer.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { findAll, mount } from './hook-harness.mjs';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
globalThis.HTMLElement = class {};
globalThis.document = { activeElement: null };

const el = (name) => ({ name, focused: 0, focus() { this.focused += 1; } });
const attr = (n, name, value) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

async function openDrawer(withField = true) {
  const calls = { closed: 0 };
  const screen = await mount('ui/Drawer.tsx', 'Drawer', {
    open: true,
    title: 'Edit',
    onClose: () => { calls.closed += 1; },
    children: { type: 'form', props: { children: [] } }
  }, {}, []);
  const find = (test) => findAll(screen.tree(), test);
  const one = (test) => find(test)[0];
  const fakes = { field: el('field'), close: el('close'), keep: el('keep'), discard: el('discard') };
  const panel = one((n) => n.props?.role === 'dialog');
  panel.props.ref.current = {
    querySelector: (sel) => (sel.includes('close-drawer') ? fakes.close : sel.startsWith('form') && withField ? fakes.field : null),
    querySelectorAll: () => [fakes.field, fakes.close]
  };
  return { screen, calls, fakes, find, one, panel };
}

const typeAndClose = async (d) => {
  d.one((n) => n.props?.className === 'drawer-body').props.onInput();
  await d.screen.settle();
  d.one((n) => attr(n, 'data-action', 'close-drawer')).props.onClick();
  await d.screen.settle();
};

test('M4 a clean drawer closes at once; a typed one asks first', async () => {
  const d = await openDrawer();
  d.one((n) => attr(n, 'data-action', 'close-drawer')).props.onClick();
  assert.equal(d.calls.closed, 1);
  const e = await openDrawer();
  await typeAndClose(e);
  assert.equal(e.calls.closed, 0);
  assert.equal(e.find((n) => n.props?.role === 'alertdialog').length, 1, 'the discard prompt is open');
});

test('M4 a submit event does not clear the guard (a refused submit must not lose the input)', async () => {
  const d = await openDrawer();
  const body = d.one((n) => n.props?.className === 'drawer-body');
  assert.equal(body.props.onSubmit, undefined, 'the body no longer clears dirty on any submit');
  await typeAndClose(d);
  assert.equal(d.calls.closed, 0, 'still asks');
});

test('M4 the caller saying "saved" (useDrawerSaved) clears the guard: the next Close closes without asking', async () => {
  const d = await openDrawer();
  d.one((n) => n.props?.className === 'drawer-body').props.onInput();
  await d.screen.settle();
  const provider = d.one((n) => typeof n.type === 'symbol' && n.type.description === 'Provider');
  assert.equal(typeof provider.props.value, 'function', 'the drawer hands its children a markSaved function');
  provider.props.value();
  await d.screen.settle();
  d.one((n) => attr(n, 'data-action', 'close-drawer')).props.onClick();
  assert.equal(d.calls.closed, 1);
});

test('M4 the Systems / Contracts forms call useDrawerSaved on their success path, not on submit', () => {
  for (const f of ['pages/systems/RegisterSystemForm.tsx', 'pages/systems/EditSystemForm.tsx', 'pages/contracts/ImportContractForm.tsx']) {
    const text = readFileSync(join(src, f), 'utf8');
    assert.match(text, /useDrawerSaved\(\)/, f);
    assert.match(text, /markSaved\(\);/, f);
  }
});

test('M3 the discard prompt is a modal alertdialog and the head and body behind it are inert', async () => {
  const d = await openDrawer();
  const inertOf = () => ['drawer-head', 'drawer-body'].map((c) => d.one((n) => n.props?.className === c).props.inert);
  assert.deepEqual(inertOf(), [undefined, undefined], 'not inert while no prompt is open');
  await typeAndClose(d);
  const box = d.one((n) => n.props?.role === 'alertdialog');
  assert.equal(box.props['aria-modal'], 'true');
  assert.deepEqual(inertOf(), ['', ''], 'the form and the Close button behind the prompt are ignored');
});

test('M3 Keep editing returns focus to the first field (else the Close button), so Esc and Tab keep working', async () => {
  const d = await openDrawer(true);
  await typeAndClose(d);
  d.one((n) => attr(n, 'data-action', 'keep-editing')).props.onClick();
  await d.screen.settle();
  assert.equal(d.fakes.field.focused, 1, 'focus lands on the first field');
  const bare = await openDrawer(false);
  await typeAndClose(bare);
  bare.one((n) => n.props?.role === 'dialog').props.onKeyDown({ key: 'Escape', stopPropagation() {} }); // Esc closes the prompt too
  await bare.screen.settle();
  assert.equal(bare.fakes.close.focused, 1, 'no field: the Close button takes focus');
});

test('M3 Tab inside the open prompt stays within the prompt', async () => {
  const d = await openDrawer();
  await typeAndClose(d);
  const box = d.one((n) => n.props?.role === 'alertdialog');
  box.props.ref.current = { querySelectorAll: () => [d.fakes.keep, d.fakes.discard] };
  globalThis.document.activeElement = d.fakes.keep;
  let prevented = 0;
  d.one((n) => n.props?.role === 'dialog').props.onKeyDown({ key: 'Tab', shiftKey: false, preventDefault() { prevented += 1; } });
  assert.equal(prevented, 1);
  assert.equal(d.fakes.discard.focused, 1, 'Tab moves to the prompt\'s next button, not to the form behind');
  assert.equal(d.fakes.field.focused, 0);
  globalThis.document.activeElement = null;
});
