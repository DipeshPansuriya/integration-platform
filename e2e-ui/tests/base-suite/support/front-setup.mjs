// lane qa-release (2026-10-22): the front half's global setup - the base suite's boot protocol
// (support/global-setup.mjs bootBaseSuite) with the FRONT options: no dev-only exception to configure, no
// local WireMock, the normal allow-list with one exact host. Nothing here writes a pipeline row.

import { frontSuiteBootOptions } from '../lib/front-options.mjs';
import { bootBaseSuite } from './global-setup.mjs';

export default async function frontSetup() {
  const options = frontSuiteBootOptions({
    lockHolder: `aditya-qa-automation base-suite front pid=${process.pid} started=${new Date().toISOString()}`
  });
  await bootBaseSuite(options, { wiremock: false });
}
