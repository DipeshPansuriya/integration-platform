// E4 (T-097, lane D): the console harness's identity provider - the REAL Keycloak, pinned to the
// tag compose/compose.yaml and TestImages.java use (R05), with realm `platform` imported from the
// same compose/keycloak/platform-realm.json the deployment mounts. Replaces E3's local fake issuer.
//
// R07: every secret (the master admin, the console client secret, user passwords) is generated
// here at random per run and handed to `docker create` through the docker CLI's own environment
// (`-e NAME` with no value), never as an argument and never in a file. Nothing is printed.
//
// sessionLogin() drives the console login the way a browser does (the backend's login entry, the
// realm's login form, the code callback) and returns the backend session cookie and CSRF token:
// the harness's server-side logins (the second bindings, the perf seed, the contract test). The
// browser itself logs in through the same form in Playwright (tests/screens/keycloak-login.mjs).

import crypto from 'node:crypto';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

export const KEYCLOAK_IMAGE = 'quay.io/keycloak/keycloak:26.8.0'; // R05: same tag as compose + TestImages
export const CONSOLE_CLIENT = 'platform-console';
const REALM = 'platform';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function random(bytes) {
  return crypto.randomBytes(bytes).toString('base64url');
}

function docker(args, env = {}, timeout = 120000) {
  const result = spawnSync('docker', args, {
    encoding: 'utf8',
    timeout,
    windowsHide: true,
    env: { ...process.env, ...env }
  });
  if (result.error || result.status !== 0) {
    // The arguments never hold a secret (values travel in the environment), so naming them is safe.
    throw new Error(`docker ${args[0]} failed (${result.status ?? result.error}): ${(result.stderr ?? '').trim()}`);
  }
  return result.stdout.trim();
}

/**
 * The `docker cp` pairs a harness container gets before it starts: the realm import (a directory
 * copy creates the import folder; a single-file copy would need it to exist) and the realm's
 * login theme `platform` where Keycloak loads themes - the same two places compose/compose.yaml
 * mounts. The themes folder rides along inside the import copy as it does in compose; the
 * importer reads only the realm JSON.
 */
export function containerCopies(repo) {
  const keycloak = path.join(repo, 'compose', 'keycloak');
  return [
    [keycloak, '/opt/keycloak/data/import'],
    [path.join(keycloak, 'themes', 'platform'), '/opt/keycloak/themes/platform']
  ];
}

function hostPort(container, port) {
  return Number(docker(['port', container, String(port)]).split('\n')[0].split(':').pop());
}

/**
 * Starts Keycloak with realm `platform`. `consoleRedirectUri` and `postLogoutRedirectUri` fill the
 * realm file's placeholders (the backend's code callback, the console after sign-out).
 */
export async function startKeycloak({ repo, audience, consoleRedirectUri, postLogoutRedirectUri }) {
  const name = `ipass-ui-kc-${crypto.randomBytes(4).toString('hex')}`;
  const secrets = {
    KC_BOOTSTRAP_ADMIN_USERNAME: `harness-admin-${crypto.randomBytes(3).toString('hex')}`,
    KC_BOOTSTRAP_ADMIN_PASSWORD: random(24),
    CONSOLE_OIDC_CLIENT_SECRET: random(32),
    PLATFORM_ADMIN_CLIENT_SECRET: random(32)
  };
  const settings = {
    KC_HEALTH_ENABLED: 'true',
    JAVA_OPTS_KC_HEAP: '-Xms64m -Xmx512m', // R06: one capped JVM in the container
    TZ: 'Etc/UTC',
    OIDC_AUDIENCE: audience,
    CONSOLE_OIDC_REDIRECT_URI: consoleRedirectUri,
    CONSOLE_POST_LOGOUT_REDIRECT_URI: postLogoutRedirectUri
  };
  const env = { ...secrets, ...settings };
  docker([
    'create', '--name', name, '-p', '127.0.0.1::8080', '-p', '127.0.0.1::9000',
    ...Object.keys(env).flatMap((key) => ['-e', key]),
    KEYCLOAK_IMAGE, 'start-dev', '--import-realm'
  ], env);
  const stop = () => {
    try {
      docker(['rm', '-f', name]);
    } catch {
      // already gone
    }
  };
  try {
    for (const [from, to] of containerCopies(repo)) docker(['cp', from, `${name}:${to}`]);
    docker(['start', name]);
    const http = hostPort(name, 8080);
    const management = hostPort(name, 9000);
    for (let i = 0; ; i++) {
      try {
        const ready = await fetch(`http://127.0.0.1:${management}/health/ready`, { signal: AbortSignal.timeout(2000) });
        if (ready.ok) break;
      } catch {
        // not up yet
      }
      if (i >= 240) throw new Error(`keycloak ${name} never became ready`);
      await sleep(1000);
    }
    // `localhost` (not 127.0.0.1): the identity provider is a different site from the console
    // (127.0.0.1:4173), as it is in a deployment; the issuer is fixed by the host every party uses.
    const base = `http://localhost:${http}`;
    const issuer = `${base}/realms/${REALM}`;

    async function adminToken() {
      const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'password',
          client_id: 'admin-cli',
          username: secrets.KC_BOOTSTRAP_ADMIN_USERNAME,
          password: secrets.KC_BOOTSTRAP_ADMIN_PASSWORD
        })
      });
      if (!response.ok) throw new Error(`keycloak admin token answered ${response.status}`);
      return (await response.json()).access_token;
    }

    /**
     * An enabled realm user; `subject` is its token `sub`. The password is random (memory only)
     * unless the caller hands one in (T94: the trial login's password from the OS secret store).
     */
    async function createUser(username, password = random(18)) {
      const response = await fetch(`${base}/admin/realms/${REALM}/users`, {
        method: 'POST',
        headers: { authorization: `Bearer ${await adminToken()}`, 'content-type': 'application/json' },
        body: JSON.stringify({
          username,
          enabled: true,
          emailVerified: true,
          firstName: 'Harness',
          lastName: username,
          email: `${username}@example.test`,
          credentials: [{ type: 'password', value: password, temporary: false }]
        })
      });
      if (response.status !== 201) throw new Error(`keycloak: creating user ${username} answered ${response.status}`);
      const location = response.headers.get('location');
      return { username, password, subject: location.slice(location.lastIndexOf('/') + 1) };
    }

    /**
     * T174b: a confidential service-account client (client-credentials only, default scopes so its tokens carry the
     * platform audience), as the Java suites' KeycloakRealmContainer.createConfidentialClient does. Returns the
     * client's credential and the service-account user's id (the token `sub`) in memory only; the caller binds that
     * subject to a tenant.
     */
    async function createServiceClient(clientId) {
      const headers = { authorization: `Bearer ${await adminToken()}`, 'content-type': 'application/json' };
      const clients = `${base}/admin/realms/${REALM}/clients`;
      const created = await fetch(clients, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          clientId, enabled: true, protocol: 'openid-connect', publicClient: false, clientAuthenticatorType: 'client-secret',
          serviceAccountsEnabled: true, standardFlowEnabled: false, directAccessGrantsEnabled: false, implicitFlowEnabled: false
        })
      });
      if (created.status !== 201) throw new Error(`keycloak: creating client ${clientId} answered ${created.status}`);
      const id = created.headers.get('location').split('/').pop();
      const forClient = await fetch(`${clients}/${id}/client-secret`, { headers });
      const account = await fetch(`${clients}/${id}/service-account-user`, { headers });
      if (!forClient.ok || !account.ok) throw new Error(`keycloak: reading client ${clientId} answered ${forClient.status}/${account.status}`);
      return { clientId, secret: (await forClient.json()).value, subject: (await account.json()).id };
    }

    return {
      name,
      issuer,
      audience,
      consoleClientId: CONSOLE_CLIENT,
      consoleClientSecret: secrets.CONSOLE_OIDC_CLIENT_SECRET,
      // T130 (row 323): the realm's platform-admin service-account secret (PLATFORM_ADMIN_CLIENT_SECRET above,
      // random per run, memory only); bootBackend hands it to the app only with `sourceCredentials`
      platformAdminClientSecret: secrets.PLATFORM_ADMIN_CLIENT_SECRET,
      createUser,
      createServiceClient,
      stop
    };
  } catch (e) {
    stop();
    throw e;
  }
}

// --- a server-side console login (the HTTP the browser would send) -----------------------------

/** A cookie jar keyed by host name - cookies are not isolated by port, as in a browser. */
export function cookieJar() {
  const jars = new Map();
  return {
    header(url) {
      const jar = jars.get(new URL(url).hostname);
      return jar && jar.size ? [...jar].map(([k, v]) => `${k}=${v}`).join('; ') : null;
    },
    remember(url, response) {
      const host = new URL(url).hostname;
      const jar = jars.get(host) ?? new Map();
      jars.set(host, jar);
      for (const line of response.headers.getSetCookie()) {
        const [pair, ...attributes] = line.split(';');
        const eq = pair.indexOf('=');
        if (eq <= 0) continue;
        const name = pair.slice(0, eq).trim();
        const value = pair.slice(eq + 1).trim();
        const expired = !value || attributes.some((a) => /^\s*max-age=0\s*$/i.test(a));
        if (expired) jar.delete(name);
        else jar.set(name, value);
      }
    },
    value(url, name) {
      return jars.get(new URL(url).hostname)?.get(name) ?? null;
    }
  };
}

export async function send(jar, url, init = {}) {
  const headers = { ...(init.headers ?? {}) };
  const cookie = jar.header(url);
  if (cookie) headers.cookie = cookie;
  const response = await fetch(url, { ...init, headers, redirect: 'manual', signal: AbortSignal.timeout(20000) });
  jar.remember(url, response);
  return response;
}

/**
 * Logs `user` in to the backend at `backendBase` through the realm's real login form. Returns the
 * session as the browser would hold it: `cookie` (SESSION + XSRF-TOKEN, for the backend's host)
 * and `xsrf` (the value the console echoes in X-XSRF-TOKEN). Throws when the login is refused.
 */
export async function sessionLogin(backendBase, user) {
  const jar = cookieJar();
  const backendHost = new URL(backendBase).host;
  let url = new URL('/oauth2/authorization/keycloak', backendBase).toString();
  let response = await send(jar, url);
  for (let hop = 0; hop < 10 && response.status >= 300 && response.status < 400; hop++) {
    url = new URL(response.headers.get('location'), url).toString();
    response = await send(jar, url);
  }
  const page = await response.text();
  const form = /<form[^>]*\bid="kc-form-login"[^>]*>/.exec(page);
  const action = form && /\baction="([^"]+)"/.exec(form[0]);
  if (!action) throw new Error(`sessionLogin(${user.username}): the realm's login form was not reached (status ${response.status})`);
  url = new URL(action[1].replaceAll('&amp;', '&'), url).toString();
  response = await send(jar, url, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: user.username, password: user.password, credentialId: '' })
  });
  // Follow the code callback back to the backend; stop at the backend's redirect to the console.
  for (let hop = 0; hop < 10 && response.status >= 300 && response.status < 400; hop++) {
    const next = new URL(response.headers.get('location'), url);
    if (next.host === backendHost && !next.pathname.startsWith('/login/') && !next.pathname.startsWith('/oauth2/')) break;
    if (next.host !== backendHost && new URL(url).host === backendHost) break; // the console on another port
    url = next.toString();
    response = await send(jar, url);
  }
  if (!jar.value(backendBase, 'SESSION')) {
    throw new Error(`sessionLogin(${user.username}): no session after the login (last status ${response.status})`);
  }
  // The console's first call: the CSRF cookie arrives with it.
  const me = await send(jar, new URL('/api/v1/me', backendBase).toString());
  await me.arrayBuffer();
  return {
    username: user.username,
    status: me.status,
    cookie: jar.header(backendBase),
    xsrf: jar.value(backendBase, 'XSRF-TOKEN')
  };
}

/** Headers of a console call with `session`: the cookie, and the CSRF header on a state-changing call. */
export function sessionHeaders(session, method = 'GET') {
  const headers = { cookie: session.cookie };
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) && session.xsrf) {
    headers['x-xsrf-token'] = session.xsrf;
  }
  return headers;
}
