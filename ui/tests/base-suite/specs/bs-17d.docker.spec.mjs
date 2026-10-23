// BS-17d (plan section 3) on the REAL boot: while the dev-only exception is active the booted application logged
// the startup WARN (SystemPersistenceConfiguration.java:115): "SHOWCASE ONLY", the named host, the port, the
// showcase profile. The Java legs (SystemPersistenceShowcaseWiringTest.java:71, ShowcaseLocalHostsHostContainerTest.java:88)
// prove it in a test context; this proves it on the app the suite boots. Read-only: the app log tap is only read.
// This file runs inside the exception run (playwright.base-suite.config.mjs with the four IPASS_BS_EXCEPTION_* settings).

import { expect, test } from '@playwright/test';
import { exceptionSettings, requireException } from '../lib/settings.mjs';
import { appLogFrom } from '../support/api.mjs';

test('BS-17d: the startup log carries the SHOWCASE ONLY WARN naming the exception host, the port and the profile', async () => {
  const settings = requireException(exceptionSettings(process.env));
  const warns = (await appLogFrom(0)).split('\n').filter((line) => line.includes('SHOWCASE ONLY'));
  expect(warns.length, 'a startup WARN while the exception is active').toBeGreaterThan(0);
  const [line] = warns;
  expect(line, 'logged at WARN').toMatch(/WARN/);
  expect(line, 'names the exception host').toContain(settings.host);
  expect(line, 'names the exception port').toContain(String(settings.port));
  expect(line, 'names the profile').toContain(settings.profile);
});
