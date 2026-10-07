// The console server for the screen protocol (T-017, T-022; real login since T-097): serves the
// BUILT console (vite build + preview API, in-process, so no webServer ordering problem) on
// http://127.0.0.1:4173 and proxies the backend's routes to the booted backend - the API
// (/api, /v3), and the login endpoints (/oauth2 start, /login code callback, /logout), as one
// origin would in a deployment. Lives inside globalSetup's process; stopped in global teardown.
//
// Login (E4, ADR-007 Decision 1): the browser logs in through the real Keycloak form and holds
// only the backend's HttpOnly session cookie; the proxy passes cookies through untouched.
//
// Harness bindings (T-022 test protocol): a request carrying the X-Harness-Login marker is sent
// with THAT binding's server-side session instead (its cookie and its CSRF token, logged in by
// boot-backend through the same realm form); the marker is stripped and so is any Set-Cookie of
// the answer, so the browser's own session is never replaced. The backend authorizes and isolates
// for real; nothing is mocked and no token ever reaches the browser.

import { preview } from 'vite';

const LOGIN_MARKER = 'x-harness-login';
const BACKEND_ROUTES = ['/api', '/v3', '/oauth2', '/login', '/logout'];

export async function startConsole(baseUrlOfBackend, sessions = {}) {
  const route = {
    target: baseUrlOfBackend,
    configure(proxy) {
      if (process.env.CONSOLE_PROXY_TRACE) {
        proxy.on('proxyRes', (proxyRes, req) => console.error(`CONSOLE-PROXY ${req.method} ${req.url} -> ${proxyRes.statusCode}`));
      }
      proxy.on('proxyReq', (proxyReq, req) => {
        const marker = req.headers[LOGIN_MARKER];
        if (typeof marker !== 'string') return;
        const session = sessions[marker];
        if (!session) throw new Error(`console-server: no harness login for marker "${marker}"`);
        proxyReq.setHeader('cookie', session.cookie);
        if (req.headers['x-xsrf-token'] !== undefined) proxyReq.setHeader('x-xsrf-token', session.xsrf);
        proxyReq.removeHeader(LOGIN_MARKER);
      });
      proxy.on('proxyRes', (proxyRes, req) => {
        if (typeof req.headers[LOGIN_MARKER] === 'string') delete proxyRes.headers['set-cookie'];
      });
    }
  };
  const server = await preview({
    preview: {
      host: '127.0.0.1', // vite binds ::1 only by default; everything here speaks 127.0.0.1
      port: 4173,
      strictPort: true,
      proxy: Object.fromEntries(BACKEND_ROUTES.map((prefix) => [prefix, route]))
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
