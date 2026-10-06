// The console server for the screen protocol (T-017, T-022): serves the BUILT console
// (vite build + preview API, in-process, so no webServer ordering problem) and proxies /api
// to the booted backend. Lives inside globalSetup's process; stopped in global teardown.
//
// Login binding (the harness's part of the OIDC seam a real deployment would do): the browser
// never holds a token; the proxy stamps the seeded configurator's bearer on every /api call.
// A test may ask for one of the harness's OTHER bindings (the approver, the other tenant) with
// the X-Harness-Login marker; the proxy swaps in that binding's token and strips the marker -
// the backend authorizes and isolates for real, nothing is mocked.

import { preview } from 'vite';

const LOGIN_MARKER = 'x-harness-login';

export async function startConsole(baseUrlOfBackend, logins = {}) {
  const defaultToken = logins.default;
  const server = await preview({
    preview: {
      host: '127.0.0.1', // vite binds ::1 only by default; everything here speaks 127.0.0.1
      port: 4173,
      strictPort: true,
      proxy: {
        '/api': {
          target: baseUrlOfBackend,
          configure(proxy) {
            proxy.on('proxyReq', (proxyReq, req) => {
              const marker = req.headers[LOGIN_MARKER];
              const token = (typeof marker === 'string' && logins[marker]) || defaultToken;
              if (!token) throw new Error(`console-server: no harness login for marker "${marker}"`);
              proxyReq.setHeader('authorization', `Bearer ${token}`);
              proxyReq.removeHeader(LOGIN_MARKER);
            });
          }
        }
      }
    }
  });
  return {
    url: 'http://127.0.0.1:4173',
    async close() {
      await server.httpServer.close();
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  };
}
