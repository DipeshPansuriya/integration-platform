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
import { CONSOLE_PORT_DEFAULT, CONSOLE_PORT_ENV, claimPort, lockCandidates } from './lib/isolation.mjs';
import { sessionHeaders, sessionLogin, startKeycloak } from './keycloak-harness.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO = path.resolve(HERE, '..', '..'); // the test project (e2e-ui) is one level below the repo root, like ui/ was
export const UI = path.join(REPO, 'ui'); // L29: the console project (build, dist, vite config); the suites live in ../e2e-ui
// R05 + F4 (row 554, CWE-1357): tag AND digest, like WireMock and Kafka. The digest is the local image's RepoDigest (docker image inspect postgres:17.11-alpine, measured 2026-10-27, re-checked 2026-10-28).
export const PG_IMAGE = 'postgres:17.11-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24';
const AUDIENCE = 'integration-platform-api';
/** The console's origin in the screen and perf protocols (tests/screens/console-server.mjs). */
export const CONSOLE_ORIGIN = `http://127.0.0.1:${CONSOLE_PORT_DEFAULT}`;
/** L22: this run's console origin - the default port unless an overlapping run holds it (bootBackend claims and publishes it). */
export const consoleOrigin = () => `http://127.0.0.1:${Number(process.env[CONSOLE_PORT_ENV]) || CONSOLE_PORT_DEFAULT}`;
// The PC-wide lock sits beside the MAIN checkout, also from a worktree - resolved exactly as
// build-checks/check-container-exclusion.sh does (git's common dir), never beside the worktree.
const LOCK_PATH = process.env.DOCKER_SUITE_LOCK ?? (() => {
  const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: REPO, encoding: 'utf8', windowsHide: true });
  const gitDir = common.status === 0 && common.stdout.trim() ? common.stdout.trim() : path.join(REPO, '.git');
  return path.resolve(path.dirname(path.dirname(gitDir)), '.docker-suite.lock');
})();

/** F1: the failure text of a docker call - argv is echoed, so a `-e NAME=value` of a secret-looking name is masked (a bare `-e NAME` stays). */
export function dockerFailure(args, status, stderr) {
  const shown = args.map((arg) => arg.replace(/^([A-Za-z0-9_]*(?:PASSWORD|SECRET|KEY|TOKEN)[A-Za-z0-9_]*)=.*$/s, '$1=***'));
  return `docker ${shown.join(' ')} failed (${status}): ${(stderr ?? '').trim()}`;
}

/** `env` is ADDED to the child's environment: a secret named in argv (`-e NAME`) is passed by value this way, never on the command line. */
function docker(args, timeout = 60000, env = undefined) {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout, windowsHide: true, ...(env ? { env: { ...process.env, ...env } } : {}) });
  if (result.error || result.status !== 0) {
    throw new Error(dockerFailure(args, result.status ?? result.error, result.stderr));
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

/**
 * L22: takes the first free docker-suite slot (.docker-suite.lock, then -1, -2, -3) - or only DOCKER_SUITE_LOCK when the
 * caller set it - with the unchanged protocol per slot: mkdir = take, owner note = who, a note older than 60 minutes is
 * stale, a live slot is never touched. Returns { path, release }; release removes only the slot this boot took.
 */
async function acquireSuiteLock(holder = `vijay-developer ui-harness ${new Date().toISOString()}`) {
  const start = Date.now();
  const candidates = lockCandidates(process.env, LOCK_PATH);
  for (;;) {
    for (const lockPath of candidates) {
      for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
          fs.mkdirSync(lockPath);
        } catch (e) {
          if (e.code !== 'EEXIST') throw e;
          const ownerFile = path.join(lockPath, 'owner');
          if (attempt === 0 && fs.existsSync(ownerFile)) {
            const ageMs = Date.now() - fs.statSync(ownerFile).mtimeMs;
            if (ageMs > 60 * 60 * 1000) {
              console.error(`docker-suite lock ${lockPath} is stale (${Math.round(ageMs / 60000)} min old): removing it`);
              fs.rmSync(lockPath, { recursive: true, force: true });
              continue;
            }
          }
          break;
        }
        writeLockNote(lockPath, holder);
        return { path: lockPath, release: () => fs.rmSync(lockPath, { recursive: true, force: true }) };
      }
    }
    if (Date.now() - start > 120 * 60 * 1000) {
      throw new Error(`could not take a docker-suite lock (${candidates.join(', ')}) within the wait window`);
    }
    await sleep(20000);
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

/**
 * The newest modification time (ms) of anything that goes into the jar: every pom.xml (root and platform-*) and
 * every file under src/main of each platform module. Test sources are left out on purpose - they are not in the jar, so a test
 * edit must not cost a two-minute rebuild. 0 when nothing is found.
 */
export function newestSourceMtime(repo = REPO) {
  let newest = 0;
  const visit = (entry) => {
    const stat = fs.statSync(entry);
    if (stat.isDirectory()) {
      for (const child of fs.readdirSync(entry)) visit(path.join(entry, child));
    } else {
      newest = Math.max(newest, stat.mtimeMs);
    }
  };
  const touch = (file) => { if (fs.existsSync(file)) newest = Math.max(newest, fs.statSync(file).mtimeMs); };
  touch(path.join(repo, 'pom.xml'));
  for (const module of fs.readdirSync(repo).filter((name) => name.startsWith('platform-'))) {
    touch(path.join(repo, module, 'pom.xml'));
    const main = path.join(repo, module, 'src', 'main');
    if (fs.existsSync(main)) visit(main);
  }
  return newest;
}

/** A jar older than any of its sources is stale: reusing it boots yesterday's product (a false red, run 9). */
export function jarIsStale(jar, repo = REPO) {
  return fs.statSync(jar).mtimeMs < newestSourceMtime(repo);
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
  'PLATFORM_ADMIN_CLIENT_SECRET',
  // T130: the broker the boot started (services.kafka) - only the harness points the app at it
  'IPASS_KAFKA_BOOTSTRAP_SERVERS', 'IPASS_KAFKA_PUBLISH_CLIENT_ID'
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

// The sidecars a boot may start (tests/base-suite/unit/boot-services.test.mjs): WireMock on its own port
// inside the container and its health path - the Java suites' pattern (ApiAdapterWireMock.java:39-61) - and
// ONE KRaft Kafka broker+controller (BS-09 reads its topics' headers; auto-create off like the app expects).
const SIDECARS = Object.freeze({
  wiremock: Object.freeze({ prefix: 'ipass-ui-bs-wm', containerPort: 8080, readyPath: '/__admin/health' }),
  kafka: Object.freeze({ prefix: 'ipass-ui-bs-kafka', containerPort: 9092 }),
  // F3 (row 523, ADR-H-IPASS-03): the target database a DB approval dials with the restricted account - see targetDbPlan
  targetDb: Object.freeze({ prefix: 'ipass-ui-bs-tdb', containerPort: 5432 })
});
const KAFKA_TOPIC = /^platform\.[a-z0-9.-]+$/;
const KAFKA_INTERNAL = 'localhost:19092'; // the in-container listener: topic admin and console consumer
const KAFKA_TOPICS_CLI = '/opt/kafka/bin/kafka-topics.sh';
const KAFKA_CLIENT_ID = 'ipass-ui-bs';

function kafkaPlan(key, sidecar, name, image, { topics } = {}, kafkaPort) {
  if (!Array.isArray(topics) || topics.length === 0 || new Set(topics).size !== topics.length
    || !topics.every((topic) => typeof topic === 'string' && KAFKA_TOPIC.test(topic))) {
    throw new Error(`boot-backend: service ${key} topics must be a non-empty list of unique platform.* topics, got ${JSON.stringify(topics)}`);
  }
  if (!Number.isInteger(kafkaPort) || kafkaPort < 1 || kafkaPort > 65535) {
    throw new Error(`boot-backend: service ${key} needs its kafkaPort (an integer 1-65535, picked before the lock), got ${kafkaPort}`);
  }
  // The advertised PLAINTEXT address must be the host's, so the host port is fixed (picked free) - not random.
  const env = [
    'KAFKA_NODE_ID=1',
    'KAFKA_PROCESS_ROLES=broker,controller',
    'KAFKA_LISTENERS=PLAINTEXT://:9092,INTERNAL://:19092,CONTROLLER://:9093',
    `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:${kafkaPort},INTERNAL://${KAFKA_INTERNAL}`,
    'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
    'KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL',
    'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
    'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
    'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false',
    'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
    'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
    'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
    'KAFKA_MIN_INSYNC_REPLICAS=1',
    'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0',
    'TZ=Etc/UTC'
  ];
  const cli = ['exec', name, KAFKA_TOPICS_CLI, '--bootstrap-server', KAFKA_INTERNAL];
  return {
    key,
    name,
    containerPort: sidecar.containerPort,
    port: kafkaPort,
    topics: [...topics],
    runArgs: ['run', '-d', '--rm', '--name', name, '-p', `127.0.0.1:${kafkaPort}:${sidecar.containerPort}`,
      ...env.flatMap((pair) => ['-e', pair]), image],
    readyArgs: [...cli, '--list'],
    topicArgs: topics.map((topic) => [...cli, '--create', '--if-not-exists', '--topic', topic, '--partitions', '3', '--replication-factor', '1']),
    appEnv: { IPASS_KAFKA_BOOTSTRAP_SERVERS: `127.0.0.1:${kafkaPort}`, IPASS_KAFKA_PUBLISH_CLIENT_ID: KAFKA_CLIENT_ID }
  };
}
const SQL_NAME = /^[a-z][a-z0-9_]{0,62}$/;

/**
 * F3 (row 523, ADR-H-IPASS-03 option A): the TARGET database a DB table approval dials. ApprovedTableService calls
 * DbAccountWidth, which opens a credentialed JDBC connection to the DB system's host:port and reads the account's
 * grants - a WireMock port cannot answer that, and no seam skips it. So the boot starts the platform's own Postgres
 * image (PG_IMAGE: reuse, no new image) on 127.0.0.1:<hostPort> (the caller picks it: the app's destination
 * allow-lists must name it before the JVM starts), creates `database` and `tables` as the owner, and nothing else:
 * the narrow login accounts (INSERT and SELECT on those tables only) are created per tenant by the worker.
 */
function targetDbPlan(key, sidecar, name, declaration = {}) {
  const { image, hostPort, database, tables } = declaration;
  if (image !== PG_IMAGE) throw new Error(`boot-backend: service ${key} image must be the platform postgres ${PG_IMAGE}, got ${image}`);
  if (!(Number.isInteger(hostPort) && hostPort >= 1 && hostPort <= 65535)) {
    throw new Error(`boot-backend: service ${key} hostPort ${hostPort} is not a TCP port (an integer 1-65535)`);
  }
  if (typeof database !== 'string' || !SQL_NAME.test(database)) throw new Error(`boot-backend: service ${key} database ${database} is not a plain name`);
  if (!Array.isArray(tables) || tables.length === 0 || new Set(tables).size !== tables.length
    || !tables.every((table) => typeof table === 'string' && SQL_NAME.test(table))) {
    throw new Error(`boot-backend: service ${key} tables must be a non-empty list of distinct plain names`);
  }
  return {
    key,
    name,
    containerPort: sidecar.containerPort,
    hostPort,
    database,
    env: { POSTGRES_PASSWORD: crypto.randomBytes(18).toString('base64url') }, // F1: by value in the spawn environment, never in argv
    runArgs: ['run', '-d', '--rm', '--name', name, '-p', `127.0.0.1:${hostPort}:${sidecar.containerPort}`,
      '-e', 'POSTGRES_USER=owner', '-e', 'POSTGRES_PASSWORD', '-e', `POSTGRES_DB=${database}`, image],
    initSql: tables.map((table) => `CREATE TABLE public.${table} (delivery_id uuid UNIQUE, payload jsonb)`)
  };
}
export const PINNED_IMAGE = /^[a-z0-9]+(?:[._/-][a-z0-9]+)*:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$/; // R05: tag AND digest
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isStubMapping(mapping) {
  const status = mapping?.response?.status;
  return UUID.test(String(mapping?.id ?? '')) && typeof mapping?.request?.method === 'string'
    && typeof mapping?.request?.urlPath === 'string' && mapping.request.urlPath.startsWith('/')
    && Number.isInteger(status) && status >= 100 && status <= 599;
}

/**
 * `services` -> the sidecars bootBackend starts INSIDE its docker-suite lock and removes before that lock is
 * released: `wiremock: { image, mappings }` and `kafka: { image, topics }` (its host port is `kafkaPort`,
 * picked by the caller, because the broker advertises it). Pure: checks the declaration and returns each
 * sidecar's container name, port, ready path, stubs and `docker run` arguments - the image pinned by tag AND
 * digest (R05), published on 127.0.0.1 only on a random host port (or the declared `hostPort`), --rm. No services -> [] (today's boot).
 */
export function servicePlan(services = {}, suffix, { kafkaPort } = {}) {
  const entries = Object.entries(services ?? {});
  if (entries.length === 0) return [];
  if (typeof suffix !== 'string' || !/^[0-9a-f]{8}$/.test(suffix)) {
    throw new Error(`boot-backend: service name suffix ${suffix} is not 8 hex characters`);
  }
  return entries.map(([key, declaration = {}]) => {
    const { image, mappings } = declaration;
    const sidecar = Object.hasOwn(SIDECARS, key) ? SIDECARS[key] : null;
    if (!sidecar) throw new Error(`boot-backend: unknown service ${key} - only ${Object.keys(SIDECARS).join(', ')} is declared`);
    if (typeof image !== 'string' || !PINNED_IMAGE.test(image)) {
      throw new Error(`boot-backend: service ${key} image must be pinned by tag AND digest (R05), got ${image}`);
    }
    if (key === 'targetDb') return targetDbPlan(key, sidecar, `${sidecar.prefix}-${suffix}`, declaration);
    if (key === 'kafka') return kafkaPlan(key, sidecar, `${sidecar.prefix}-${suffix}`, image, declaration, kafkaPort);
    if (!Array.isArray(mappings)) throw new Error(`boot-backend: service ${key} needs its stub mappings (an array)`);
    for (const mapping of mappings) {
      if (!isStubMapping(mapping)) throw new Error(`boot-backend: ${JSON.stringify(mapping)} is not a stub mapping (uuid id, method, /path, status)`);
    }
    const { hostPort } = declaration;
    if (hostPort !== undefined && !(Number.isInteger(hostPort) && hostPort >= 1 && hostPort <= 65535)) {
      throw new Error(`boot-backend: service ${key} hostPort ${hostPort} is not a TCP port (an integer 1-65535)`);
    }
    const name = `${sidecar.prefix}-${suffix}`;
    return {
      key,
      name,
      containerPort: sidecar.containerPort,
      readyPath: sidecar.readyPath,
      mappings: [...mappings],
      runArgs: ['run', '-d', '--rm', '--name', name, '-p', `127.0.0.1:${hostPort ?? ''}:${sidecar.containerPort}`, image]
    };
  });
}

const TENANT_CODE = /^[a-z0-9][a-z0-9-]{0,62}$/;
const REALM_ISSUER = /^https?:\/\/[A-Za-z0-9.-]+(?::\d{1,5})?\/realms\/[A-Za-z0-9_-]+$/;

/**
 * One planned extra tenant (extraTenantPlan) -> the owner-psql statements that bind it, in the harness's own
 * pattern (the bindings in bootBackend): the tenant row, then per login its app_user row (the realm user's
 * issuer + subject) and its role_grant row (ADR-007 Decision 5). Every value is checked for its shape first,
 * so none can leave its SQL literal.
 */
export function extraTenantSql({ code, logins } = {}, issuer, subjects = {}) {
  if (typeof code !== 'string' || !TENANT_CODE.test(code)) throw new Error(`boot-backend: tenant code ${code} is not a tenant code`);
  if (typeof issuer !== 'string' || !REALM_ISSUER.test(issuer)) throw new Error(`boot-backend: issuer ${issuer} is not a realm issuer URL`);
  const statements = [`INSERT INTO public.tenant (code, status) VALUES ('${code}', 'ACTIVE')`];
  for (const [login, role] of logins ?? []) {
    const subject = subjects[login];
    if (subject === undefined) throw new Error(`boot-backend: login ${login} has no realm user`);
    if (typeof subject !== 'string' || !UUID.test(subject)) throw new Error(`boot-backend: the subject of ${login} is not a realm user id`);
    if (typeof role !== 'string' || !/^[A-Z_]+$/.test(role)) throw new Error(`boot-backend: the role of ${login} is not a role name`);
    statements.push(
      `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${issuer}', '${subject}', 'ACTIVE' FROM public.tenant WHERE code='${code}'`,
      `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, '${role}' FROM public.app_user WHERE issuer='${issuer}' AND subject='${subject}'`
    );
  }
  return statements;
}

async function healthy(appPort) {
  try {
    const response = await fetch(`http://127.0.0.1:${appPort}/actuator/health`, { signal: AbortSignal.timeout(2000) });
    return response.ok ? null : new Error(`health answered ${response.status} - not healthy yet`);
  } catch {
    return new Error('health not answered yet');
  }
}

/**
 * B35 (row 372): the SQL that runs BEFORE the app starts, as the owner. The app login reaches schema `scheduler`
 * only as a member of ipaas_app (V2_2_1400 grants USAGE to that role alone), and db-scheduler registers the job
 * register at the app's own startup - so the membership must exist first, exactly as a deployment grants it
 * (GrantAppRoleFlywayCallback.java, E18Base.java GrantAppRole). Flyway's V1_3_1 accepts a pre-existing ipaas_app
 * with these attributes, so the role is created here with exactly V1_3_1's attributes (idempotent).
 */
export function appRoleBootSql(loginName) {
  if (typeof loginName !== 'string' || !/^[a-z_][a-z0-9_]*$/.test(loginName)) {
    throw new Error(`appRoleBootSql: login name must be a plain lower-case identifier, got ${JSON.stringify(loginName)}`);
  }
  return [
    "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ipaas_app') THEN "
      + 'CREATE ROLE ipaas_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION; END IF; END $$',
    `GRANT ipaas_app TO ${loginName}`
  ];
}

/**
 * B35: db-scheduler swallows a startup failure ("Unexpected error while executing OnStartup tasks. Continuing.")
 * and the app still answers healthy with an empty job register. Returns the first such log line, or null.
 */
export function schedulerStartupFailure(logChunks) {
  const text = Array.isArray(logChunks) ? logChunks.join('') : String(logChunks ?? '');
  return text.split(/\r?\n/).find((line) => line.includes('Unexpected error while executing OnStartup tasks')) ?? null;
}

/** A psql failure that is only the cluster's start-up window (initdb's temporary server, the database not created yet). */
export function transientPsqlFailure(out) {
  return ['shutting down', 'still starting up', 'the database system', 'No such file or directory', 'Connection refused'].some((text) => out.includes(text))
    || /FATAL: {2}database "[a-z0-9_]+" does not exist/.test(out);
}

export function psql(container, sql, database = 'platform') {
  // The initdb phase runs a temporary postgres that answers pg_isready and then shuts down
  // (measured 2026-10-05, lane D: FATAL "the database system is shutting down" after an early
  // CREATE ROLE; measured 2026-10-06 run 4: "connection to server on socket ... No such file
  // or directory" between pg_isready and the real cluster's socket). Retry transient
  // startup/shutdown states until the REAL cluster is up.
  for (let i = 0; i < 60; i++) {
    const result = spawnSync('docker', ['exec', container, 'psql', '-U', 'owner', '-d', database, '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8', windowsHide: true });
    if (result.status === 0) return result.stdout.trim();
    const out = `${result.stdout ?? ''}${result.stderr ?? ''}`;
    if (!transientPsqlFailure(out)) {
      throw new Error(`psql failed (${result.status}): ${out.trim()}`);
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500); // the cluster is still coming up: pause between tries
  }
  throw new Error('psql: the real postgres cluster never became ready');
}

/**
 * T130: a started WireMock sidecar (servicePlan) - its loopback port, then its health (bounded: 2 s per probe,
 * 2 minutes in all, the Java suites' startup timeout), then each declared stub through its admin API. Returns
 * its address; throws (and the boot tears down) when it never answers or refuses a stub.
 */
async function readyWireMock({ key, name, containerPort, readyPath, mappings }) {
  const port = Number(docker(['port', name, String(containerPort)]).split('\n')[0].split(':').pop());
  const url = `http://127.0.0.1:${port}`;
  for (let i = 0; ; i++) {
    try {
      const ready = await fetch(`${url}${readyPath}`, { signal: AbortSignal.timeout(2000) });
      if (ready.ok) break;
    } catch {
      // not up yet
    }
    if (i >= 240) throw new Error(`boot-backend: sidecar ${name} never answered ${readyPath}`);
    await sleep(500);
  }
  for (const mapping of mappings) {
    const stub = await fetch(`${url}/__admin/mappings`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(mapping),
      signal: AbortSignal.timeout(5000)
    });
    if (stub.status !== 201) throw new Error(`boot-backend: ${key} refused the stub for ${mapping.request.urlPath} (${stub.status})`);
  }
  return { name, port, url, adminUrl: `${url}/__admin` };
}

/**
 * T130: a started Kafka sidecar (servicePlan) - polled until its topic admin answers (bounded: 240 x 500 ms,
 * like WireMock), then each declared topic created (auto-create is off). Throws (the boot tears down) when it
 * never answers or a topic is refused.
 */
async function readyKafka({ name, port, topics, readyArgs, topicArgs }) {
  for (let i = 0; ; i++) {
    const ready = spawnSync('docker', readyArgs, { encoding: 'utf8', timeout: 20000 });
    if (ready.status === 0) break;
    if (i >= 240) throw new Error(`boot-backend: sidecar ${name} never answered kafka-topics --list: ${ready.stderr}`);
    await sleep(500);
  }
  for (const args of topicArgs) docker(args, 60000);
  return { name, port, bootstrap: `127.0.0.1:${port}`, topics: [...topics] };
}

/** T130: removes the started sidecars one by one - a failure is reported and never skips the rest or the lock release. */
function removeSidecars(names) {
  for (const name of names) {
    try {
      docker(['rm', '-f', name]);
    } catch (e) {
      console.error(`teardown: sidecar ${name}: ${e.message}`);
    }
  }
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
 *
 * T130 (row 323, F21 BS-B) - four ADDITIVE options, all off by default (none given = exactly the boot below):
 * `extraTenants` NEW tenants `${tenantCode}-${key}` with NEW realm users, bound like the harness's own and
 * each given a harness session (extraTenantPlan, extraTenantSql); `services` sidecars started INSIDE this
 * boot's docker-suite lock and removed before it is released (servicePlan - WireMock, Kafka); `appEnv` app
 * settings ADDED through mergeAppEnv (the dev-only exception's profile and host); `sourceCredentials` hands
 * the app the realm's platform-admin client secret by its NAME PLATFORM_ADMIN_CLIENT_SECRET (a random value,
 * memory only - keycloak-harness.mjs), so source credentials are issued through the real API. Every option
 * is checked before the lock is taken.
 */
export default async function bootBackend({
  loginSeed = false, extraUsers = [], lockHolder, extraTenants = [], services = {}, appEnv = {}, sourceCredentials = false
} = {}) {
  extraTenantPlan('lane-d-000000', extraTenants); // T130: a bad option never takes the lock or starts a container
  // T130: the broker advertises its host port, so it is picked here (free now, re-checked by docker run)
  const kafkaPort = services?.kafka ? await freePort() : undefined;
  const sidecars = servicePlan(services, crypto.randomBytes(4).toString('hex'), { kafkaPort });
  const kafkaEnv = sidecars.find((sidecar) => sidecar.key === 'kafka')?.appEnv ?? {};
  mergeAppEnv(kafkaEnv, appEnv);
  if (typeof sourceCredentials !== 'boolean') throw new Error('boot-backend: sourceCredentials is true or false');
  const suiteLock = await acquireSuiteLock(lockHolder);
  let consoleClaim; // L22: released with the lock, so a second boot in this process can reuse the port
  const releaseLock = () => { consoleClaim?.release(); suiteLock.release(); };
  let container, app, keycloak;
  const sidecarNames = []; // T130: every started sidecar, removed before the lock is released
  try {
    // Inside the try (m-910e28): a boot that throws before the teardown path would leak the lock.
    const jdk = resolveJdk(); // resolves before any container starts - a missing JVM fails the boot immediately
    // L22: the console's port is claimed here (default 4173 when free) and published for console-server.mjs and the specs
    consoleClaim = await claimPort(Number(process.env[CONSOLE_PORT_ENV]) || CONSOLE_PORT_DEFAULT);
    process.env[CONSOLE_PORT_ENV] = String(consoleClaim.port);
    const origin = consoleOrigin();
    const pgName = `ipass-ui-t41-pg-${crypto.randomBytes(4).toString('hex')}`;
    const pgPassword = crypto.randomBytes(18).toString('base64url'); // memory-only (R07)
    const loginName = `console_login_${crypto.randomBytes(5).toString('hex')}`;
    const loginPassword = crypto.randomBytes(18).toString('base64url');

    let jar = jarPath();
    if (!jar || jarIsStale(jar)) {
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
      postLogoutRedirectUri: `${origin}/`
    });
    if (sourceCredentials && typeof keycloak.platformAdminClientSecret !== 'string') {
      throw new Error('boot-backend: sourceCredentials, but the realm handed no platform-admin client secret');
    }

    docker(['run', '-d', '--rm', '--name', pgName, '-P', '-e', 'POSTGRES_USER=owner', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB=platform', PG_IMAGE], 60000, { POSTGRES_PASSWORD: pgPassword });
    container = pgName;
    const pgPort = Number(docker(['port', pgName, '5432']).split(':').pop());
    for (let i = 0; i < 60; i++) {
      if (spawnSync('docker', ['exec', pgName, 'pg_isready', '-U', 'owner'], { windowsHide: true }).status === 0) break;
      if (i === 59) throw new Error(`postgres container ${pgName} never became ready`);
      await sleep(500);
    }

    // T130: the declared sidecars (none by default), inside this boot's lock and before the JVM starts, so
    // one that will not come up fails the boot early. servicePlan declares WireMock and Kafka.
    const serviceInfo = {};
    for (const sidecar of sidecars) {
      docker(sidecar.runArgs, 60000, sidecar.env);
      sidecarNames.push(sidecar.name);
      if (sidecar.key === 'targetDb') {
        for (const statement of sidecar.initSql) psql(sidecar.name, statement, sidecar.database); // psql retries until the real cluster is up
        serviceInfo[sidecar.key] = { name: sidecar.name, port: sidecar.hostPort, database: sidecar.database };
        continue;
      }
      serviceInfo[sidecar.key] = sidecar.key === 'kafka' ? await readyKafka(sidecar) : await readyWireMock(sidecar);
    }

    psql(pgName, `CREATE ROLE ${loginName} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '${loginPassword}'`);
    // B35: the login joins ipaas_app BEFORE the app starts (a deployment does the same); see appRoleBootSql.
    for (const statement of appRoleBootSql(loginName)) psql(pgName, statement);

    const spawnApp = () => spawn(
      jdk.java,
      ['-XX:+UseSerialGC', '-Xmx256m', `-Dserver.port=${appPort}`, '-jar', jar],
      {
        // T130: `appEnv` is ADDED through mergeAppEnv (it never replaces a name the harness owns); only with
        // `sourceCredentials` does the app get the realm's admin client secret, by its NAME (value in memory)
        env: mergeAppEnv({
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
          CONSOLE_POST_LOGOUT_REDIRECT_URI: `${origin}/`,
          CONSOLE_URL: `${origin}/`,
          ...(sourceCredentials ? { PLATFORM_ADMIN_CLIENT_SECRET: keycloak.platformAdminClientSecret } : {}),
          ...kafkaEnv // T130: harness-owned - the app publishes to the broker this boot started
        }, appEnv),
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true
      }
    );
    const appLog = [];
    const startApp = () => {
      app = spawnApp();
      app.stdout.on('data', (d) => appLog.push(String(d)));
      app.stderr.on('data', (d) => appLog.push(String(d)));
    };
    startApp();

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

    // B35: a scheduler that failed to register its jobs at startup is a failed boot, not a quiet log line.
    const schedulerFailure = schedulerStartupFailure(appLog);
    if (schedulerFailure !== null) {
      throw new Error(`platform-app started but db-scheduler could not register its jobs: ${schedulerFailure.slice(0, 400)}`);
    }

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
      // T112 (row 328 M2): a tenant-B user that HOLDS the read role, so a tenant-isolation spec proves the
      // tenant cut (404 / empty list) rather than the role check the CONFIGURATOR binding hits first.
      'other-tenant-reader': await keycloak.createUser('lane-d-other-tenant-reader'),
      'empty-tenant': await keycloak.createUser('lane-d-empty-tenant')
    };
    // E4 T-099 seeding: roles are role_grant rows (ADR-007 Decision 5). The default login authors AND is refused
    // its own approval on screen, so it must pass the role check: tenant admin (admins may also approve).
    const roles = {
      default: 'TENANT_ADMIN',
      approver: 'APPROVER',
      'other-tenant': 'CONFIGURATOR',
      'other-tenant-reader': 'READ_ONLY',
      'empty-tenant': 'TENANT_ADMIN'
    };
    const tenantOf = { 'other-tenant': otherTenantCode, 'other-tenant-reader': otherTenantCode, 'empty-tenant': emptyTenantCode };
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

    // T130: the extra tenants (none by default) - NEW tenant rows and NEW realm users, bound in the same
    // pattern as the harness's own above; each of their logins also gets a harness session below.
    const tenantUsers = {};
    const addedTenants = {};
    for (const tenant of extraTenantPlan(tenantCode, extraTenants)) {
      const logins = {};
      for (const [login, role] of tenant.logins) {
        tenantUsers[login] = await keycloak.createUser(login);
        logins[login] = { username: tenantUsers[login].username, subject: tenantUsers[login].subject, role };
      }
      const subjects = Object.fromEntries(Object.entries(logins).map(([login, { subject }]) => [login, subject]));
      for (const sql of extraTenantSql(tenant, keycloak.issuer, subjects)) psql(pgName, sql);
      addedTenants[tenant.key] = { code: tenant.code, logins };
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
    for (const [login, user] of Object.entries(tenantUsers)) { // T130: the extra tenants' logins, the same way
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
      /** T130: the `extraTenants` by key - tenant code and its logins (username, subject, role; no password). */
      extraTenants: addedTenants,
      /** T130: the started sidecars by key - container name, loopback port, URL, admin URL ({} by default). */
      services: serviceInfo,
      /** T130: the app's process id - one process serves the whole run (BS-07's "0 deploys"). */
      appPid: app.pid,
      /** The docker-suite lock this boot holds; refreshLock() rewrites its owner note (mtime now). */
      lockPath: suiteLock.path,
      refreshLock() {
        writeLockNote(suiteLock.path, lockHolder ?? 'ui-harness');
      },
      /** T174b: a Keycloak service-account client (clientId, secret, subject) - memory only. */
      createServiceClient: (clientId) => keycloak.createServiceClient(clientId),
      /** T171: stops the JVM and starts it again on the same port, env and database; resolves once healthy (a session-survives-restart check). */
      async restartApp() {
        const old = app;
        const exited = new Promise((resolve) => old.once('exit', resolve));
        old.kill();
        await exited;
        startApp();
        for (let i = 0; i < 240; i++) {
          if (app.exitCode !== null) throw new Error(`platform-app exited ${app.exitCode} after the restart`);
          if ((await healthy(appPort)) === null) return;
          await sleep(500);
        }
        throw new Error('platform-app never became healthy after the restart');
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
        removeSidecars(sidecarNames); // T130: before the lock is released (none by default)
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
    removeSidecars(sidecarNames); // T130: before the lock is released (none by default)
    keycloak?.stop();
    releaseLock();
    throw e;
  }
}
