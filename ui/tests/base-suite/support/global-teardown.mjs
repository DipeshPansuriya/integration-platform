// T130 (row 323, F21 BS-B): the base suite's global teardown - console server, then the booted backend
// (its WireMock sidecar, containers and JVM, then the docker-suite lock).

import { teardownAll } from './boot-state.mjs';

export default async function globalTeardown() {
  await teardownAll();
}
