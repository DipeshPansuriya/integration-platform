import assert from 'node:assert/strict';
import { test } from 'node:test';
import { demoLaunchOptions } from './demo-mode.mjs';

test('off by default: no launch option changes, so every suite stays headless and fast', () => {
  assert.deepEqual(demoLaunchOptions({}), {});
  assert.deepEqual(demoLaunchOptions({ E2E_DEMO: '0' }), {});
});

test('E2E_DEMO=1: headed with the default 700 ms pause before each action', () => {
  const options = demoLaunchOptions({ E2E_DEMO: '1' });
  assert.equal(options.headless, false);
  assert.equal(options.slowMo, 700);
});

test('E2E_DEMO_SLOWMO_MS sets the pause; a bad value fails before any boot', () => {
  assert.equal(demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: '1200' }).slowMo, 1200);
  assert.equal(demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: '0' }).slowMo, 0);
  for (const bad of ['abc', '-1', '5001', '1.5']) {
    assert.throws(() => demoLaunchOptions({ E2E_DEMO: '1', E2E_DEMO_SLOWMO_MS: bad }), /E2E_DEMO_SLOWMO_MS/, bad);
  }
});
