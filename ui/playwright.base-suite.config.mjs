// T130 (row 323, F21 BS-B): the base-version release suite's own Playwright config (BS-07, BS-12 and
// the scenarios that follow). The shape and its reasons live in tests/base-suite/lib/suite-config.mjs.
// Run: npx playwright test -c playwright.base-suite.config.mjs (docker-suite command; the screens
// suite's playwright.config.mjs is untouched and never reads tests/base-suite).

import { defineConfig } from '@playwright/test';
import { BASE_SUITE_CONFIG } from './tests/base-suite/lib/suite-config.mjs';

export default defineConfig(BASE_SUITE_CONFIG);
