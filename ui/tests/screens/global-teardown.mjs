// Playwright global teardown: stop the console server and the booted backend (drops the
// containers and the docker-suite lock).

import { teardownAll } from './boot-state.mjs';

export default async function globalTeardown() {
  await teardownAll();
}