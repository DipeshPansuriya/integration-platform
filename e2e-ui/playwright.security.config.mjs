// T171 (row 372): the Release 1 security gate's browser cases - S1-A1..A3 (identity) and S3-A1..A6 (the BFF session).
// A container suite in effect: the global setup boots the same real backend + Keycloak as the screens suite (under
// the docker-suite lock; set DOCKER_SUITE_LOCK to the slot you hold) and adds the environment names the security
// specs need. `--list` boots nothing.
//   DOCKER_SUITE_LOCK=<slot dir> npx playwright test -c playwright.security.config.mjs --reporter=line --trace on
// workers 1 / retries 0: a flaky case is reported, never retried to green.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests/security',
  testMatch: '**/*.docker.spec.mjs',
  timeout: 180000,
  workers: 1,
  retries: 0,
  globalSetup: 'tests/security/global-setup.mjs',
  globalTeardown: 'tests/security/global-teardown.mjs',
  use: { headless: true, trace: 'on' }
});
