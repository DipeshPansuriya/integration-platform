// T-158 / T-017 / T-022 / T-096 (E3 lane D), moved to the real login by T-097 (E4 lane D): the
// console's container harness.
//
// Boots the REAL backend for the console's tests: the built platform-app jar against its own
// pinned PostgreSQL container and the REAL Keycloak (realm `platform` from the compose realm file,
// scripts/keycloak-harness.mjs), with seeded login bindings. The console logs in exactly as in a
// deployment: Authorization Code + PKCE through the backend (ADR-007 Decision 1), a server
// session cookie, never a token in the browser. The harness's own server-side logins (the second
// bindings, the seeds) go through the same realm login form (sessionLogin). Same fixed
// environment variable names as PlatformTestWiring.java (R07), random secrets in memory only
// (R07), the pinned image tags of compose/compose.yaml + TestImages.java (R05).
//
// R06 one-docker-suite-at-a-time: this module takes the PC-wide lock at
// D:/Projects/ipass/.docker-suite.lock - the same file build-checks/check-container-exclusion.sh
// takes - never wrapping a Maven gate (its own lock is never nested). A lock older than 60
// minutes is stale and removed with a note. Java secrets: values are generated at runtime and
// never printed or committed (R07).

import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sessionHeaders, sessionLogin, startKeycloak } from './keycloak-harness.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const UI = path.resolve(HERE, '..');
export const REPO = path.resolve(UI, '..');
export const PG_IMAGE = 'postgres:17.11-alpine'; // R05: the exact tag compose/compose.yaml + TestImages.java use
const AUDIENCE = 'integration-platform-api';
/** The console's origin in the screen and perf protocols (tests/screens/console-server.mjs). */
export const CONSOLE_ORIGIN = 'http://127.0.0.1:4173';
// The PC-wide lock sits beside the MAIN checkout, also from a worktree - resolved exactly as
// build-checks/check-container-exclusion.sh does (git's common dir), never beside the worktree.
const LOCK_PATH = process.env.DOCKER_SUITE_LOCK ?? (() => {
  const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: REPO, encoding: 'utf8', windowsHide: true });
  const gitDir = common.status === 0 && common.stdout.trim() ? common.stdout.trim() : path.join(REPO, '.git');
  return path.resolve(path.dirname(path.dirname(gitDir)), '.docker-suite.lock');
})();

function docker(args, timeout = 60000) {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout, windowsHide: true });
  if (result.error || result.status !== 0) {
    throw new Error(`docker ${args.join(' ')} failed (${result.status ?? result.error}): ${(result.stderr ?? '').trim()}`);
  }
  return result.stdout.trim();
}

function isWin() {
  return process.platform === 'win32';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// --- the one-docker-suite-at-a-time lock (R06, plan:98) -------------------------------

/**
 * Writes the lock's owner note: the holder line, then when it was refreshed. Rewriting it moves the
 * note's mtime to now - the age every lock taker (this module, check-container-exclusion.sh) reads
 * as "stale after 60 minutes" - so a holder that stays up (T94's trial) keeps it fresh. Never
 * re-creates a lock directory that is gone (the write throws ENOENT instead).
 */
export function writeLockNote(lockDir, holder) {
  fs.writeFileSync(path.join(lockDir, 'owner'), `${holder} refreshed=${new Date().toISOString()}
`, { flag: 'w' });
}

async function acquireSuiteLock(holder = `vijay-developer ui-harness ${new Date().toISOString()}`) {
  const start = Date.now();
  for (;;) {
    try {
      fs.mkdirSync(LOCK_PATH);
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      const ownerFile = path.join(LOCK_PATH, 'owner');
      if (fs.existsSync(ownerFile)) {
        const ageMs = Date.now() - fs.statSync(ownerFile).mtimeMs;
        if (ageMs > 60 * 60 * 1000) {
          console.error(`docker-suite lock is stale (${Math.round(ageMs / 60000)} min old): removing it`);
          fs.rmSync(LOCK_PATH, { recursive: true, force: true });
          continue;
        }
      }
      if (Date.now() - start > 120 * 60 * 1000) {
        throw new Error(`could not take the docker-suite lock at ${LOCK_PATH} within the wait window`);
      }
      await sleep(20000);
      continue;
    }
    writeLockNote(LOCK_PATH, holder);
    return () => fs.rmSync(LOCK_PATH, { recursive: true, force: true });
  }
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
}

// --- the platform-app jar -------------------------------------------------------------

function jarPath() {
  const target = path.join(REPO, 'platform-app', 'target');
  if (!fs.existsSync(target)) return null;
  const jars = fs.readdirSync(target).filter((f) => f.startsWith('platform-app-') && f.endsWith('.jar'));
  // The executable jar is the "-exec" one (platform-app pom.xml:95); the plain artifact
  // (a few kB) stays for arch-test and cannot run.
  const executable = jars.find((f) => f.endsWith('-exec.jar'));
  const big = jars.filter((f) => fs.statSync(path.join(target, f)).size > 10000)[0];
  const chosen = executable ?? big;
  return chosen ? path.join(target, chosen) : null;
}

function buildJar(jdk) {
  console.log('boot-backend: building platform-app (mvnw -pl platform-app -am package -DskipTests)');
  // Win11 26300 with NoDefaultCurrentDirectoryInExePath: cmd.exe (spawned via shell:true) does
  // NOT resolve a bare name against the cwd - 'mvnw.cmd' dies "is not recognized". The Maven
  // wrapper's own docs spawn it by absolute path; the non-Windows branch keeps './mvnw'.
  const cmd = isWin() ? path.resolve(REPO, 'mvnw.cmd') : './mvnw';
  const result = spawnSync(
    cmd,
    ['-pl', 'platform-app', '-am', 'package', '-DskipTests'],
    {
      cwd: REPO,
      env: {
        ...jdkEnv(process.env, jdk),
        JAVA_TOOL_OPTIONS: `${process.env.JAVA_TOOL_OPTIONS ?? ''} -Duser.timezone=Etc/UTC`.trim(), // R04
        MAVEN_OPTS: `${process.env.MAVEN_OPTS ?? '-Xmx384m -Xms64m'}`.trim()
      },
      encoding: 'utf8',
      shell: isWin()
    }
  );
  const tail = `${result.stdout ?? ''}\n${result.stderr ?? ''}`.split('\n').slice(-15).join('\n');
  if (result.status !== 0) {
    throw new Error(`mvnw package failed (${result.status}):\n${tail}`);
  }
}

// --- the JDK (mvnw and java) ------------------------------------------------------------

function javaIn(home) {
  for (const name of ['java.exe', 'java']) {
    const candidate = path.join(home, 'bin', name);
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      return candidate;
    } catch {}
  }
  return null;
}

function probeJavaOnPath() {
  const probe = spawnSync('java', ['-version'], { encoding: 'utf8', windowsHide: true });
  return !probe.error && probe.status === 0;
}

const versionParts = (name) => (name.match(/\d+/g) ?? []).map(Number);
function newerFirst(a, b) {
  const [x, y] = [versionParts(a), versionParts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] ?? -1) !== (y[i] ?? -1)) return (y[i] ?? -1) - (x[i] ?? -1);
  }
  return 0;
}

/**
 * The JDK for mvnw and the app (JDK 25, AGENTS.md): JAVA_HOME's bin/java first (the shell's PATH
 * may not carry it through npm), then java on PATH, then the newest JDK 25 folder under
 * <homeDir>/.jdks (where the IDE downloads it - a PC can have no JAVA_HOME and no java on PATH).
 * Returns { javaHome, java }; javaHome is null when java comes from PATH. Never changes any
 * environment - the caller hands the result to its children through jdkEnv(). Throws naming all
 * three places it looked.
 */
export function resolveJdk({ env = process.env, homeDir = os.homedir(), javaOnPath = probeJavaOnPath } = {}) {
  const home = env.JAVA_HOME ?? '';
  const fromHome = home ? javaIn(home) : null;
  if (fromHome) return { javaHome: home, java: fromHome };
  if (javaOnPath()) return { javaHome: null, java: 'java' };
  const jdks = path.join(homeDir, '.jdks');
  let names = [];
  try {
    names = fs.readdirSync(jdks).filter((name) => /^(?:[A-Za-z]+-)?25(?:[.+-]|$)/.test(name));
  } catch {}
  for (const name of names.sort(newerFirst)) {
    const java = javaIn(path.join(jdks, name));
    if (java) return { javaHome: path.join(jdks, name), java };
  }
  throw new Error(`boot-backend: no JVM found (JAVA_HOME=${home || '<unset>'}${home ? ' has no bin/java' : ''}, `
    + `PATH: no java, ${jdks}: no JDK 25 folder with bin/java)`);
}

/** A child env for mvnw / java: the resolved JDK's JAVA_HOME and its bin first on PATH (the
 *  existing PATH key kept - Windows' "Path" never gets a second "PATH" beside it). */
export function jdkEnv(baseEnv, jdk) {
  if (!jdk.javaHome) return { ...baseEnv };
  const pathKey = Object.keys(baseEnv).find((key) => key.toUpperCase() === 'PATH') ?? 'PATH';
  const bin = path.join(jdk.javaHome, 'bin');
  return {
    ...baseEnv,
    JAVA_HOME: jdk.javaHome,
    [pathKey]: baseEnv[pathKey] ? `${bin}${path.delimiter}${baseEnv[pathKey]}` : bin
  };
}

// --- T130 (row 323, F21 BS-B): additive two-tenant options --------------------------------
// ADD, never repurpose (tests/base-suite/unit/boot-plan.test.mjs): the logins bound below and the app
// settings the harness passes are its own - an extra tenant or extra app setting can never take one, so
// with no extras the boot is exactly what it was before.

export const HARNESS_LOGINS = Object.freeze(['default', 'approver', 'other-tenant', 'empty-tenant']);
const HARNESS_TENANT_KEYS = Object.freeze(['b', 'c']); // `${tenantCode}-b` (the other tenant), `-c` (the empty one)
export const HARNESS_APP_ENV = Object.freeze([
  'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_MIGRATION_USERNAME', 'DB_MIGRATION_PASSWORD',
  'OIDC_ISSUER_URI', 'OIDC_AUDIENCE', 'CONSOLE_OIDC_CLIENT_ID', 'CONSOLE_OIDC_CLIENT_SECRET',
  'CONSOLE_OIDC_REDIRECT_URI', 'CONSOLE_POST_LOGOUT_REDIRECT_URI', 'CONSOLE_URL',
  // the JVM's own settings: an extra here could swap the app's real host resolver (jdk.net.hosts.file)
  'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_HOME', 'PATH',
  // the realm's admin client secret: only the harness itself passes it (sourceCredentials), never an extra
  'PLATFORM_ADMIN_CLIENT_SECRET'
]);

/**
 * `extraTenants` -> the tenants to add: each `{ key, logins: { <login>: <ROLE> } }` becomes the NEW tenant
 * `${tenantCode}-${key}` with NEW realm users bound in the role_grant vocabulary. A harness login or
 * tenant is never taken, nothing is given twice, a role is a closed-set name (the extraUsers check).
 */
export function extraTenantPlan(tenantCode, extraTenants = []) {
  const keys = new Set();
  const logins = new Set();
  return (extraTenants ?? []).map(({ key, logins: wanted } = {}) => {
    if (HARNESS_TENANT_KEYS.includes(key)) throw new Error(`boot-backend: tenant key ${key} is a harness tenant (-b other, -c empty) - add a new one`);
    if (typeof key !== 'string' || !/^[a-z][a-z0-9-]{1,20}$/.test(key)) throw new Error(`boot-backend: invalid tenant key ${key}`);
    if (keys.has(key)) throw new Error(`boot-backend: tenant key ${key} given twice`);
    keys.add(key);
    const entries = Object.entries(wanted ?? {});
    if (entries.length === 0) throw new Error(`boot-backend: extra tenant ${key} has no logins`);
    for (const [login, role] of entries) {
      if (HARNESS_LOGINS.includes(login)) throw new Error(`boot-backend: harness login ${login} is never repurposed - name a new login`);
      if (!/^[a-z][a-z0-9-]{1,30}$/.test(login)) throw new Error(`boot-backend: invalid login ${login}`);
      if (logins.has(login)) throw new Error(`boot-backend: login ${login} given twice`);
      if (typeof role !== 'string' || !/^[A-Z_]+$/.test(role)) throw new Error(`boot-backend: invalid role for ${login}`);
      logins.add(login);
    }
    return { key, code: `${tenantCode}-${key}`, logins: entries };
  });
}

/**
 * The app's environment plus extra settings (the dev-only exception's profile and host, a sidecar's
 * address): an extra ADDS a name - it never replaces one the harness owns (HARNESS_APP_ENV, compared
 * case-insensitively as Windows does), never repeats one, and is always text.
 */
export function mergeAppEnv(base, ...extras) {
  const owned = new Set(HARNESS_APP_ENV.map((name) => name.toUpperCase()));
  const added = new Set();
  const merged = { ...base };
  for (const extra of extras) {
    for (const [name, value] of Object.entries(extra ?? {})) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`boot-backend: app setting ${name} is not an environment name`);
      if (owned.has(name.toUpperCase())) throw new Error(`boot-backend: app setting ${name} is harness-owned - an extra never replaces it`);
      if (added.has(name.toUpperCase())) throw new Error(`boot-backend: app setting ${name} given twice`);
      if (typeof value !== 'string') throw new Error(`boot-backend: app setting ${name} must be text`);
      added.add(name.toUpperCase());
      merged[name] = value;
    }
  }
  return merged;
}

/** T130 step 5 RED stub (tests/base-suite/unit/boot-services.test.mjs). */
export function servicePlan() {
  throw new Error('not implemented');
}

/** T130 step 5 RED stub (tests/base-suite/unit/boot-services.test.mjs). */
export function extraTenantSql() {
  throw new Error('not implemented');
}

async function healthy(appPort) {
  try {
    const response = await fetch(`http://127.0.0.1:${appPort}/actuator/health`, { signal: AbortSignal.timeout(2000) });
    return response.ok ? null : new Error(`health answered ${response.status} - not healthy yet`);
  } catch {
    return new Error('health not answered yet');
  }
}

function psql(container, sql) {
  // The initdb phase runs a temporary postgres that answers pg_isready and then shuts down
  // (measured 2026-10-05, lane D: FATAL "the database system is shutting down" after an early
  // CREATE ROLE; measured 2026-10-06 run 4: "connection to server on socket ... No such file
  // or directory" between pg_isready and the real cluster's socket). Retry transient
  // startup/shutdown states until the REAL cluster is up.
  for (let i = 0; i < 60; i++) {
    const result = spawnSync('docker', ['exec', container, 'psql', '-U', 'owner', '-d', 'platform', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8', windowsHide: true });
    if (result.status === 0) return result.stdout.trim();
    const out = `${result.stdout ?? ''}${result.stderr ?? ''}`;
    if (!out.includes('shutting down') && !out.includes('still starting up') && !out.includes('the database system')
      && !out.includes('No such file or directory') && !out.includes('Connection refused')) {
      throw new Error(`psql failed (${result.status}): ${out.trim()}`);
    }
  }
  throw new Error('psql: the real postgres cluster never became ready');
}

// --- one booted backend ---------------------------------------------------------------

/** The backend's own OpenAPI document, fetched from the really running app (never a mock), with a console session. */
export async function fetchOpenApiDocument(boot) {
  const response = await fetch(new URL('/v3/api-docs', boot.baseUrl), { headers: boot.authHeaders() });
  if (!response.ok) {
    throw new Error(`GET /v3/api-docs answered ${response.status} - the backend OpenAPI document is not reachable through the security seam`);
  }
  return response.json();
}

/**
 * `extraUsers` (T94, the trial): more realm users bound in the default tenant, each
 * `{ key, username, password, role }` - the password is the caller's (never generated or printed
 * here) and no harness session is opened for them. `lockHolder` names the holder in the lock note.
 */
export default async function bootBackend({ loginSeed = false, extraUsers = [], lockHolder } = {}) {
  const releaseLock = await acquireSuiteLock(lockHolder);
  let container, app, keycloak;
  try {
    // Inside the try (m-910e28): a boot that throws before the teardown path would leak the lock.
    const jdk = resolveJdk(); // resolves before any container starts - a missing JVM fails the boot immediately
    const pgName = `ipass-ui-t41-pg-${crypto.randomBytes(4).toString('hex')}`;
    const pgPassword = crypto.randomBytes(18).toString('base64url'); // memory-only (R07)
    const loginName = `console_login_${crypto.randomBytes(5).toString('hex')}`;
    const loginPassword = crypto.randomBytes(18).toString('base64url');

    let jar = jarPath();
    if (!jar) {
      buildJar(jdk);
      jar = jarPath();
    }
    if (!jar) throw new Error('platform-app jar not found after the maven build');

    // The backend's port is chosen first: the realm's redirect URI (the code callback) names it.
    const appPort = await freePort();
    const baseUrl = `http://127.0.0.1:${appPort}`;
    keycloak = await startKeycloak({
      repo: REPO,
      audience: AUDIENCE,
      consoleRedirectUri: `${baseUrl}/login/oauth2/code/keycloak`,
      postLogoutRedirectUri: `${CONSOLE_ORIGIN}/`
    });

    docker(['run', '-d', '--rm', '--name', pgName, '-P', '-e', 'POSTGRES_USER=owner', '-e', `POSTGRES_PASSWORD=${pgPassword}`, '-e', 'POSTGRES_DB=platform', PG_IMAGE]);
    container = pgName;
    const pgPort = Number(docker(['port', pgName, '5432']).split(':').pop());
    for (let i = 0; i < 60; i++) {
      if (spawnSync('docker', ['exec', pgName, 'pg_isready', '-U', 'owner'], { windowsHide: true }).status === 0) break;
      if (i === 59) throw new Error(`postgres container ${pgName} never became ready`);
      await sleep(500);
    }

    psql(pgName, `CREATE ROLE ${loginName} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '${loginPassword}'`);

    app = spawn(
      jdk.java,
      ['-XX:+UseSerialGC', '-Xmx256m', `-Dserver.port=${appPort}`, '-jar', jar],
      {
        env: {
          ...jdkEnv(process.env, jdk),
          JAVA_TOOL_OPTIONS: `${process.env.JAVA_TOOL_OPTIONS ?? ''} -Duser.timezone=Etc/UTC`.trim(),
          DB_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/platform`,
          DB_USERNAME: loginName,
          DB_PASSWORD: loginPassword,
          DB_MIGRATION_USERNAME: 'owner',
          DB_MIGRATION_PASSWORD: pgPassword,
          OIDC_ISSUER_URI: keycloak.issuer,
          OIDC_AUDIENCE: AUDIENCE,
          // E4 (T-097): the console login - the realm's confidential console client (tenancy-console.yml).
          CONSOLE_OIDC_CLIENT_ID: keycloak.consoleClientId,
          CONSOLE_OIDC_CLIENT_SECRET: keycloak.consoleClientSecret,
          CONSOLE_OIDC_REDIRECT_URI: `${baseUrl}/login/oauth2/code/keycloak`,
          CONSOLE_POST_LOGOUT_REDIRECT_URI: `${CONSOLE_ORIGIN}/`,
          CONSOLE_URL: `${CONSOLE_ORIGIN}/`
        },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true
      }
    );
    const appLog = [];
    app.stdout.on('data', (d) => appLog.push(String(d)));
    app.stderr.on('data', (d) => appLog.push(String(d)));

    let lastError = null;
    for (let i = 0; i < 240; i++) {
      if (app.exitCode !== null) {
        throw new Error(`platform-app exited ${app.exitCode}:\n${appLog.slice(-40).join('')}`);
      }
      lastError = await healthy(appPort);
      if (lastError === null) break;
      await sleep(500);
    }
    if (lastError !== null) {
      throw new Error(`platform-app never became healthy: ${lastError.message}`);
    }

    // The app login is a member of ipaas_app once Flyway has created the role (ADR-009) - the
    // same step PlatformTestWiring.grantApplicationRole does for the Java suite.
    psql(pgName, `GRANT ${'ipaas_app'} TO ${loginName}`);

    // The harness's bindings: realm users, bound through the directory the migrations own (owner
    // psql; the app login only reads the directory through ipaas_login_bootstrap, ADR-009) to the
    // (issuer, subject) of their Keycloak account. The configurator and the approver (T-022: never
    // the author) are users of tenant A; one configurator belongs to ANOTHER tenant (E3-F1-S1
    // @negative @tenant, E4-F1-S1 own tenant only). A third, EMPTY tenant C holds one tenant admin
    // and never any message/delivery rows, so a spec that must see "no work at all" (F10 queue
    // dashboard) is isolated from the specs that seed tenant A's state tables (row 310).
    const tenantCode = `lane-d-${crypto.randomBytes(3).toString('hex')}`;
    const otherTenantCode = `${tenantCode}-b`;
    const emptyTenantCode = `${tenantCode}-c`;
    psql(pgName, `INSERT INTO public.tenant (code, status) VALUES ('${tenantCode}', 'ACTIVE')`);
    psql(pgName, `INSERT INTO public.tenant (code, status) VALUES ('${otherTenantCode}', 'ACTIVE')`);
    psql(pgName, `INSERT INTO public.tenant (code, status) VALUES ('${emptyTenantCode}', 'ACTIVE')`);
    const users = {
      default: await keycloak.createUser('lane-d-configurator'),
      approver: await keycloak.createUser('lane-d-approver'),
      'other-tenant': await keycloak.createUser('lane-d-other-tenant'),
      'empty-tenant': await keycloak.createUser('lane-d-empty-tenant')
    };
    // E4 T-099 seeding: roles are role_grant rows (ADR-007 Decision 5). The default login authors AND is refused
    // its own approval on screen, so it must pass the role check: tenant admin (admins may also approve).
    const roles = { default: 'TENANT_ADMIN', approver: 'APPROVER', 'other-tenant': 'CONFIGURATOR', 'empty-tenant': 'TENANT_ADMIN' };
    const tenantOf = { 'other-tenant': otherTenantCode, 'empty-tenant': emptyTenantCode };
    for (const [login, user] of Object.entries(users)) {
      const code = tenantOf[login] ?? tenantCode;
      psql(pgName,
        `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${keycloak.issuer}', '${user.subject}', 'ACTIVE' FROM public.tenant WHERE code='${code}'`);
      psql(pgName,
        `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, '${roles[login]}' FROM public.app_user WHERE issuer='${keycloak.issuer}' AND subject='${user.subject}'`);
    }
    const extra = {};
    for (const { key, username, password, role } of extraUsers) {
      if (!/^[A-Z_]+$/.test(role)) throw new Error(`boot-backend: extra user ${key} has an invalid role`);
      const user = await keycloak.createUser(username, password);
      extra[key] = { username: user.username, subject: user.subject, role };
      psql(pgName,
        `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${keycloak.issuer}', '${user.subject}', 'ACTIVE' FROM public.tenant WHERE code='${tenantCode}'`);
      psql(pgName,
        `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, '${role}' FROM public.app_user WHERE issuer='${keycloak.issuer}' AND subject='${user.subject}'`);
    }

    // The harness's server-side sessions, each through the realm's real login form. The console
    // proxy swaps one in for the X-Harness-Login marker; the browser never holds any of them.
    const sessions = {};
    for (const [login, user] of Object.entries(users)) {
      sessions[login] = await sessionLogin(baseUrl, user);
      if (sessions[login].status !== 200) {
        throw new Error(`boot-backend: the ${login} session's first call answered ${sessions[login].status}`);
      }
    }

    async function call(login, method, urlPath, body) {
      const response = await fetch(new URL(urlPath, baseUrl), {
        method,
        headers: { ...sessionHeaders(sessions[login], method), ...(body ? { 'content-type': 'application/json' } : {}) },
        body: body ? JSON.stringify(body) : undefined
      });
      return { status: response.status, data: await response.json().catch(() => null) };
    }

    // E3-F1-S1's onboarding premise, through the REAL systems seam (T-015): the source system S
    // the flow-definition and lifecycle screens name is registered for the seeded tenant - and for
    // the other tenant, whose one flow must never appear on tenant A's screens (E4-F1-S1), and for
    // the empty tenant C (a system row only - no flow, message or delivery is seeded there).
    for (const login of ['default', 'other-tenant', 'empty-tenant']) {
      const systemSeed = await call(login, 'POST', '/api/v1/admin/systems', { code: 'S', name: 'orders source', direction: 'INBOUND' });
      if (systemSeed.status !== 201 && systemSeed.status !== 409) {
        throw new Error(`boot-backend: seeding the source system (${login}) answered ${systemSeed.status} (expected 201/409)`);
      }
    }
    const otherFlow = await call('other-tenant', 'POST', '/api/v1/admin/flows',
      { sourceSystem: 'S', destinations: ['T-OTHER'], businessKeyRule: 'customer_id' });
    if (otherFlow.status !== 201) {
      throw new Error(`boot-backend: seeding the other tenant's flow answered ${otherFlow.status}`);
    }
    // The login scenario's tenant-A flow row, only when asked for: the perf suite's seed
    // verification counts tenant A's rows exactly (run-perf-suite.mjs), so an unconditional seed
    // here would silently make 51 the seeded row count (measured 2026-10-07, lane D).
    let ownFlow = null;
    if (loginSeed) {
      ownFlow = await call('default', 'POST', '/api/v1/admin/flows',
        { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
      if (ownFlow.status !== 201) {
        throw new Error(`boot-backend: seeding tenant A's flow answered ${ownFlow.status}`);
      }
    }

    return {
      baseUrl,
      issuer: keycloak.issuer,
      pgName,
      tenantCode,
      ownTenantFlowId: ownFlow?.data?.id,
      otherTenantCode,
      otherTenantFlowId: otherFlow.data.id,
      emptyTenantCode,
      seededVia: 'directory rows (tenant + app_user) as the migration owner for realm users; systems and flows through the real API with console sessions',
      /** The realm users (passwords in memory only) - the browser logs in with `users.default`. */
      users,
      /** The `extraUsers` bound in the default tenant, by key: username, subject, role (no password). */
      extraUsers: extra,
      /** The docker-suite lock this boot holds; refreshLock() rewrites its owner note (mtime now). */
      lockPath: LOCK_PATH,
      refreshLock() {
        writeLockNote(LOCK_PATH, lockHolder ?? 'ui-harness');
      },
      /** The harness's server-side console sessions per login (cookie + CSRF token, never a token). */
      sessions,
      call,
      logDump() {
        return appLog.join('');
      },
      /** Headers of a backend call with the default login's session (CSRF header on writes). */
      authHeaders(method = 'GET') {
        return sessionHeaders(sessions.default, method);
      },
      async teardown() {
        try {
          if (app) app.kill();
          if (container) docker(['rm', '-f', container]);
        } catch (e) {
          console.error(`teardown: ${e.message}`);
        }
        keycloak?.stop();
        releaseLock();
      }
    };
  } catch (e) {
    if (app) app.kill();
    if (container) {
      try {
        docker(['rm', '-f', container]);
      } catch {
        // already gone
      }
    }
    keycloak?.stop();
    releaseLock();
    throw e;
  }
}
