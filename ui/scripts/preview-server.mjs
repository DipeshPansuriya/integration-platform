// The screen-test web server (T-017/T-022 protocol): serves the BUILT console (vite build +
// preview) and proxies /api to the booted backend (API_PROXY_TARGET, a run-time environment
// NAME - R07). Spawned by Playwright's webServer after global-setup has booted the backend.

import process from 'node:process';
import { preview } from 'vite';

const proxyTarget = process.env.API_PROXY_TARGET;
if (!proxyTarget) {
  console.error('preview-server: API_PROXY_TARGET not set - did global-setup boot the backend?');
  process.exit(2);
}

const server = await preview({
  preview: {
    port: 4173,
    strictPort: true,
    proxy: { '/api': proxyTarget }
  }
});
console.log(`preview-server: console at http://localhost:4173, /api -> ${proxyTarget}`);
process.on('SIGTERM', () => server.httpServer.close());