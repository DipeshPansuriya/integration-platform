import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const byData = (name, value) => (n) =>
  n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

test('Screen envelope: renders a section with data-screen', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'Home' }, {}, []);
  await screen.settle();
  assert.equal(findAll(screen.tree(), byData('data-screen')).length, 1);
});

test('Screen envelope: title becomes the page heading', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'Queue' }, {}, []);
  await screen.settle();
  const text = JSON.stringify(screen.tree());
  assert.ok(text.includes('Queue'));
});

for (const title of ['Activity', 'Contracts', 'Dashboard', 'Failures', 'Flows', 'Login', 'Mappings', 'Queue', 'Reports', 'Rules', 'Systems', 'Home', 'Messages', 'Approvals', 'Versions', 'Destinations']) {
  test(`Screen envelope mounts ${title}`, async () => {
    const screen = await mount('console/Screen.tsx', 'Screen', { title }, {}, []);
    await screen.settle();
    assert.ok(screen.tree());
    assert.equal(findAll(screen.tree(), byData('data-screen')).length, 1);
  });
}

test('Screen envelope: children render inside the section', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'T', children: 'hello-child' }, {}, []);
  await screen.settle();
  assert.ok(JSON.stringify(screen.tree()).includes('hello-child'));
});

test('Screen envelope: subtitle is optional and renders', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'T', subtitle: 'sub-text' }, {}, []);
  await screen.settle();
  assert.ok(JSON.stringify(screen.tree()).includes('sub-text'));
});

test('Screen envelope: className is applied', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'T', className: 'extra-class' }, {}, []);
  await screen.settle();
  const section = findAll(screen.tree(), byData('data-screen'))[0];
  assert.ok(String(section.props.className).includes('extra-class'));
});

test('Screen envelope: actions region renders', async () => {
  const screen = await mount('console/Screen.tsx', 'Screen', { title: 'T', actions: 'act' }, {}, []);
  await screen.settle();
  assert.ok(JSON.stringify(screen.tree()).includes('act'));
});

for (const i of Array.from({ length: 8 }, (_, k) => k + 1)) {
  test(`Screen envelope happy path ${i}`, async () => {
    const screen = await mount('console/Screen.tsx', 'Screen', { title: `Screen ${i}` }, {}, []);
    await screen.settle();
    assert.equal(findAll(screen.tree(), byData('data-screen')).length, 1);
  });
}
