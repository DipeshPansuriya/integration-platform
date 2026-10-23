// T171: the security gate suite's boot = the screens boot (real backend, real Keycloak, built console behind the
// proxy) plus what only these specs need, published as environment names for the workers (values live in this
// run's memory only, R07): the suite's postgres container (so a stray orphan container is never picked), the
// backend base URL, the other-tenant login, the realm's console client secret (the throwaway of this Keycloak
// container, for the PKCE / single-use code checks at the token endpoint) and a loopback control endpoint
// that restarts the JVM (S3-A3: a session must survive it).

import crypto from 'node:crypto';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import { bootScreens } from '../screens/global-setup.mjs';
import { bootedBackend } from '../screens/boot-state.mjs';
import { setControl } from './control-state.mjs';

function consoleClientSecretOf(issuer) {
  const port = new URL(issuer).port;
  const rows = execFileSync('docker', ['ps', '--format', '{{.Names}}\t{{.Ports}}'], { encoding: 'utf8' }).split('\n');
  const names = rows.map((row) => row.split('\t')).filter(([, ports]) => ports && ports.includes(`:${port}->8080`)).map(([name]) => name);
  if (names.length !== 1) throw new Error(`security global-setup: exactly one keycloak container on port ${port} expected, saw ${names.length}`);
  const env = execFileSync('docker', ['inspect', '-f', '{{range .Config.Env}}{{println .}}{{end}}', names[0]], { encoding: 'utf8' });
  const line = env.split('\n').find((entry) => entry.startsWith('CONSOLE_OIDC_CLIENT_SECRET='));
  if (!line) throw new Error('security global-setup: the keycloak container carries no console client secret');
  return line.slice('CONSOLE_OIDC_CLIENT_SECRET='.length);
}

export default async function globalSetup() {
  await bootScreens({ lockHolder: `aditya t171-pw security-gate ${new Date().toISOString()}` });
  const backend = bootedBackend();

  process.env.SEC_PG = backend.pgName;
  process.env.SEC_BACKEND = backend.baseUrl;
  process.env.SEC_OTHER_TENANT_CODE = backend.otherTenantCode;
  process.env.SEC_OTHER_USER = backend.users['other-tenant'].username;
  process.env.SEC_OTHER_PASSWORD = backend.users['other-tenant'].password;
  process.env.SEC_CONSOLE_CLIENT_SECRET = consoleClientSecretOf(backend.issuer);

  const token = crypto.randomBytes(16).toString('hex');
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url.startsWith('/log') && req.headers['x-control-token'] === token) {
      // diagnostics for an unexpected answer: the application's own log lines that match ?grep= (last 80)
      const grep = new RegExp(new URL(req.url, 'http://x').searchParams.get('grep') ?? 'ERROR|Exception');
      res.writeHead(200, { 'content-type': 'text/plain' }).end(backend.logDump().split(/\r?\n/).filter((line) => grep.test(line)).slice(-80).join(String.fromCharCode(10)));
      return;
    }
    if (req.method !== 'POST' || req.url !== '/restart' || req.headers['x-control-token'] !== token) {
      res.writeHead(404).end();
      return;
    }
    try {
      await backend.restartApp();
      res.writeHead(200).end('restarted');
    } catch (e) {
      res.writeHead(500).end(String(e.message));
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  process.env.SEC_CONTROL = `http://127.0.0.1:${server.address().port}`;
  process.env.SEC_CONTROL_TOKEN = token;

  // the lock note goes stale after 60 minutes without a rewrite - keep it fresh for the whole run
  const refresh = setInterval(() => backend.refreshLock(), 10 * 60 * 1000);
  refresh.unref();
  setControl({ server, refresh });
}
