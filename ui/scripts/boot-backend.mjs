// T-158 / T-017 / T-022 / T-096 (E3 lane D): the console's container harness.
//
// Boots the REAL backend for the console's tests: the built platform-app jar against its own
// pinned PostgreSQL container, with a LOCAL test identity provider (an HTTP server this script
// serves; its tokens validate because the app is pointed at its JWKS) and a seeded login binding.
// This is exactly the wiring PlatformTestWiring.java:47 does for the Java container tests,
// reproduced here without touching any platform-*.java source: the same fixed environment
// variable names (R07), a generated random password that exists in memory only (R07), the same
// pinned image tag as compose/compose.yaml + TestImages.java (R05).
//
// R06 one-docker-suite-at-a-time: this module takes the PC-wide lock at
// D:/Projects/ipass/.docker-suite.lock - the same file build-checks/check-container-exclusion.sh
// takes - never wrapping a Maven gate (its own lock is never nested). A lock older than 60
// minutes is stale and removed with a note. Java secrets: values are generated at runtime and
// never printed or committed (R07).

import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { generateKeyPair, SignJWT, exportJWK } from 'jose';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const UI = path.resolve(HERE, '..');
export const REPO = path.resolve(UI, '..');
export const PG_IMAGE = 'postgres:17.11-alpine'; // R05: the exact tag compose/compose.yaml + TestImages.java use
const AUDIENCE = 'integration-platform-api';
const LOCK_PATH = process.env.DOCKER_SUITE_LOCK ?? path.resolve(REPO, '..', '.docker-suite.lock');

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

async function acquireSuiteLock() {
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
    fs.writeFileSync(path.join(LOCK_PATH, 'owner'), `laneD-t37 ${new Date().toISOString()}`);
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

// --- the local test identity provider -------------------------------------------------

const KEY_KID = 'lane-d-local-test-key';

async function startIdentityProvider() {
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const jwk = await exportJWK(publicKey);
  jwk.kid = KEY_KID;
  jwk.use = 'sig';
  jwk.alg = 'RS256';

  let issuer = ''; // known only once the port is bound
  const server = createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    // Spring fetches <issuer>/.well-known/openid-configuration, i.e. WITH the /realms/platform
    // prefix (measured 2026-10-05: the bare path 404ed and every token validated as 401).
    const discoveryPath = issuer ? new URL(issuer).pathname : '/realms/platform';
    if (req.url === '/.well-known/openid-configuration' || req.url === `${discoveryPath}/.well-known/openid-configuration`) {
      res.end(JSON.stringify({ issuer, jwks_uri: `${issuer}/protocol/openid-connect/certs` }));
      return;
    }
    if (req.url === '/protocol/openid-connect/certs' || req.url === `${discoveryPath}/protocol/openid-connect/certs`) {
      res.end(JSON.stringify({ keys: [jwk] }));
      return;
    }
    res.statusCode = 404;
    res.end();
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  issuer = `http://127.0.0.1:${server.address().port}/realms/platform`;

  function token(subject) {
    return new SignJWT({})
      .setProtectedHeader({ alg: 'RS256', kid: KEY_KID })
      .setIssuer(issuer)
      .setSubject(subject)
      .setAudience(AUDIENCE)
      .setIssuedAt()
      .setExpirationTime('10m')
      .sign(privateKey);
  }

  return { issuer, token, server };
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

function buildJar() {
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
        ...process.env,
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

/** The backend's own OpenAPI document, fetched from the really running app (never a mock). */
export async function fetchOpenApiDocument(boot) {
  const response = await fetch(new URL('/v3/api-docs', boot.baseUrl), { headers: boot.authHeaders() });
  if (!response.ok) {
    throw new Error(`GET /v3/api-docs answered ${response.status} - the backend OpenAPI document is not reachable through the security seam`);
  }
  return response.json();
}

export default async function bootBackend() {
  const releaseLock = await acquireSuiteLock();
  let container, app, idp;
  try {
    const pgName = `ipass-ui-t37-pg-${crypto.randomBytes(4).toString('hex')}`;
    const pgPassword = crypto.randomBytes(18).toString('base64url'); // memory-only (R07)
    const loginName = `console_login_${crypto.randomBytes(5).toString('hex')}`;
    const loginPassword = crypto.randomBytes(18).toString('base64url');

    let jar = jarPath();
    if (!jar) {
      buildJar();
      jar = jarPath();
    }
    if (!jar) throw new Error('platform-app jar not found after the maven build');

    idp = await startIdentityProvider();

    docker(['run', '-d', '--rm', '--name', pgName, '-P', '-e', 'POSTGRES_USER=owner', '-e', `POSTGRES_PASSWORD=${pgPassword}`, '-e', 'POSTGRES_DB=platform', PG_IMAGE]);
    container = pgName;
    const pgPort = Number(docker(['port', pgName, '5432']).split(':').pop());
    for (let i = 0; i < 60; i++) {
      if (spawnSync('docker', ['exec', pgName, 'pg_isready', '-U', 'owner'], { windowsHide: true }).status === 0) break;
      if (i === 59) throw new Error(`postgres container ${pgName} never became ready`);
      await sleep(500);
    }

    psql(pgName, `CREATE ROLE ${loginName} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '${loginPassword}'`);

    const appPort = await freePort();
    app = spawn(
      'java',
      ['-XX:+UseSerialGC', '-Xmx256m', `-Dserver.port=${appPort}`, '-jar', jar],
      {
        env: {
          ...process.env,
          JAVA_TOOL_OPTIONS: `${process.env.JAVA_TOOL_OPTIONS ?? ''} -Duser.timezone=Etc/UTC`.trim(),
          DB_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/platform`,
          DB_USERNAME: loginName,
          DB_PASSWORD: loginPassword,
          DB_MIGRATION_USERNAME: 'owner',
          DB_MIGRATION_PASSWORD: pgPassword,
          OIDC_ISSUER_URI: idp.issuer,
          OIDC_AUDIENCE: AUDIENCE
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

    // The seeded configurator: bound through the directory the migrations own (owner psql; the app
    // login only reads the directory through ipaas_login_bootstrap, ADR-009).
    const tenantCode = `lane-d-${crypto.randomBytes(3).toString('hex')}`;
    const subject = 'lane-d-configurator';
    psql(pgName, `INSERT INTO public.tenant (code, status) VALUES ('${tenantCode}', 'ACTIVE')`);
    psql(pgName,
      `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${idp.issuer}', '${subject}', 'ACTIVE' FROM public.tenant WHERE code='${tenantCode}'`);

    // The harness's extra bindings (T-022/E3-F2-S1 and E3-F1-S1 @negative @tenant): a second user
    // of tenant A (the approver who is never the author) and one configurator of ANOTHER tenant.
    // Same directory pattern; the tokens below never leave the harness (the console proxy swaps
    // them in for the X-Harness-Login marker - no token is ever sent to the browser).
    const approverSubject = 'lane-d-approver';
    const otherTenantCode = `${tenantCode}-b`;
    const otherTenantSubject = 'lane-d-other-tenant';
    psql(pgName,
      `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${idp.issuer}', '${approverSubject}', 'ACTIVE' FROM public.tenant WHERE code='${tenantCode}'`);
    psql(pgName, `INSERT INTO public.tenant (code, status) VALUES ('${otherTenantCode}', 'ACTIVE')`);
    psql(pgName,
      `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, '${idp.issuer}', '${otherTenantSubject}', 'ACTIVE' FROM public.tenant WHERE code='${otherTenantCode}'`);

    const token = await idp.token(subject);
    const baseUrl = `http://127.0.0.1:${appPort}`;

    // E3-F1-S1's onboarding premise, through the REAL systems seam (T-015): the source system S
    // the flow-definition and lifecycle screens name is registered for the seeded tenant.
    const systemSeed = await fetch(new URL('/api/v1/admin/systems', baseUrl), {
      method: 'POST',
      headers: { ...{ Authorization: `Bearer ${token}` }, 'content-type': 'application/json' },
      body: JSON.stringify({ code: 'S', name: 'orders source', direction: 'INBOUND' })
    });
    if (systemSeed.status !== 201 && systemSeed.status !== 409) {
      throw new Error(`boot-backend: seeding the source system answered ${systemSeed.status} (expected 201/409)`);
    }

    return {
      baseUrl,
      issuer: idp.issuer,
      pgName,
      subject,
      tenantCode,
      seededVia: 'directory rows (tenant + app_user) as the migration owner; the source system through the real systems API',
      tokens: {
        default: token,
        approver: await idp.token(approverSubject),
        'other-tenant': await idp.token(otherTenantSubject)
      },
      logDump() {
        return appLog.join('');
      },
      async tokenFor(otherSubject = subject) {
        return idp.token(otherSubject);
      },
      authHeaders() {
        return { Authorization: `Bearer ${token}` };
      },
      async teardown() {
        try {
          if (app) app.kill();
          if (container) docker(['rm', '-f', container]);
        } catch (e) {
          console.error(`teardown: ${e.message}`);
        }
        idp.server.close();
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
    idp?.server.close();
    releaseLock();
    throw e;
  }
}