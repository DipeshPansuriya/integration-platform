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

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { preview } from 'vite';

// L29: the suites moved out of ui/, so the process cwd is no longer the console project: name it.
const CONSOLE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'ui');

const LOGIN_MARKER = 'x-harness-login';
const BACKEND_ROUTES = ['/api', '/v3', '/oauth2', '/login', '/logout'];

export async function startConsole(baseUrlOfBackend, sessions = {}) {
  const route = {
    target: baseUrlOfBackend,
    configure(proxy) {
      if (process.env.CONSOLE_PROXY_TRACE) {
        proxy.on('proxyReq', (proxyReq, req) => {
          const names = String(req.headers.cookie ?? '').split('; ').map((pair) => pair.split('=')[0]).filter(Boolean);
          console.error(`CONSOLE-REQ ${req.method} ${req.url} cookies=[${names.join(',')}] xsrf=${req.headers['x-xsrf-token'] ? String(req.headers['x-xsrf-token']).slice(0, 12) : '<none>'}`);
        });
        proxy.on('proxyRes', (proxyRes, req) => {
          const setc = (proxyRes.headers['set-cookie'] ?? []).map((c) => c.slice(0, 110)).join(' | ');
          console.error(`CONSOLE-PROXY ${req.method} ${req.url} -> ${proxyRes.statusCode}${setc ? ` set-cookie=${setc}` : ''}`);
        });
      }
      proxy.on('proxyReq', (proxyReq, req, res) => {
        const marker = req.headers[LOGIN_MARKER];
        if (typeof marker !== 'string') return;
        const session = Object.hasOwn(sessions, marker) ? sessions[marker] : null;
        if (!session) {
          // T94: this listener runs on the proxy's socket event, so a throw here is an uncaught
          // exception that kills the console (and the trial). Refuse the request instead; it never
          // reaches the backend and never borrows any other session.
          console.error(`console-server: no harness login for marker "${marker}" - refused`);
          proxyReq.destroy();
          if (!res.headersSent) res.writeHead(403, { 'content-type': 'text/plain' });
          res.end(`no harness login for marker "${marker}"`);
          return;
        }
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
    root: CONSOLE_ROOT,
    preview: {
      host: '127.0.0.1', // vite binds ::1 only by default; everything here speaks 127.0.0.1
      port: Number(process.env.IPASS_UI_CONSOLE_PORT) || 4173, // L22: bootBackend claims it (4173 when free)
      strictPort: true,
      proxy: Object.fromEntries(BACKEND_ROUTES.map((prefix) => [prefix, route]))
    }
  });
  return {
    url: `http://127.0.0.1:${Number(process.env.IPASS_UI_CONSOLE_PORT) || 4173}`,
    async close() {
      await server.httpServer.close();
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  };
}
