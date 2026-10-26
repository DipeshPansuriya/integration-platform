// lane qa-release (2026-10-22): the base suite's front half - scenarios that need NO dev-only exception.
// Run from ui/ (docker-suite command; it takes the docker-suite lock through bootBackend):
//   npx playwright test -c tests/base-suite/playwright.front.config.mjs --reporter=line --trace on

import { defineConfig } from '@playwright/test';
import { frontConfig } from './lib/front-config.mjs';

export default defineConfig(frontConfig());
