// L22: run isolation for the docker-using suites, so a screens suite, the Release 1 gate, E18 and a -Pcontainer
// Maven suite can overlap on one host. Pure helpers (injectable io) shared by boot-backend, the gate runner and the
// specs. Defaults are unchanged when a suite runs alone: the preferred port is used when it is free, the first
// docker-suite slot is taken first, and only a collision moves a run to another slot or port.
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { SLOT_NAMES } from '../release1/slot.mjs';

export const SUITE_PG_PREFIX = 'ipass-ui-t41-pg-';
export const SUITE_PG = /^ipass-ui-t41-pg-[0-9a-f]{8}$/;
export const PG_ENV = 'IPASS_UI_PG_CONTAINER';
export const CONSOLE_PORT_ENV = 'IPASS_UI_CONSOLE_PORT';
export const CONSOLE_PORT_DEFAULT = 4173;

/** A short run id (8 hex) for container names that must not repeat between overlapping runs. */
export const runId = (bytes = crypto.randomBytes(4)) => bytes.toString('hex');

/** 0 for `.docker-suite.lock`, N for `.docker-suite.lock-N`, 0 for any other path (a private lock). */
export function slotIndex(lockPath) {
  const match = /^\.docker-suite\.lock(?:-(\d+))?$/.exec(path.basename(lockPath));
  return match ? Number(match[1] ?? 0) : 0;
}

/** The locks a suite may take, in order: only DOCKER_SUITE_LOCK when the caller set it, else the four PC-wide slots. */
export function lockCandidates(env, defaultLock) {
  if (typeof env.DOCKER_SUITE_LOCK === 'string' && env.DOCKER_SUITE_LOCK !== '') return [env.DOCKER_SUITE_LOCK];
  const dir = path.dirname(defaultLock);
  return SLOT_NAMES.map((name) => path.join(dir, name));
}

/** The suite postgres this run booted: the name its setup published, else the one running `ipass-ui-t41-pg-*`. */
export function pickSuitePg(env, running) {
  const named = env[PG_ENV];
  if (typeof named === 'string' && named !== '') {
    if (!SUITE_PG.test(named)) throw new Error(`${PG_ENV} "${named}" is not a suite postgres name (ipass-ui-t41-pg-<8 hex>)`);
    return named;
  }
  const names = running.map((name) => name.trim()).filter((name) => name.startsWith(SUITE_PG_PREFIX));
  if (names.length !== 1) throw new Error(`exactly one booted suite postgres expected, saw: ${names.join(', ')}`);
  return names[0];
}

export function isFree(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.listen(port, host, () => server.close(() => resolve(true)));
  });
}

export function ephemeralPort(host = '127.0.0.1') {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, host, () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

const pidAlive = (pid) => {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
};

/**
 * Claims a host port for this process: the preferred port when it is free and unclaimed, else a free ephemeral one.
 * A claim is a directory `<dir>/<port>` (mkdir = take, owner file = pid), so two runs that start minutes apart - before
 * either has bound its port - still cannot choose the same one. A claim whose pid is dead is reclaimed; a live
 * claim is never removed. release() removes only our own claim (also at process exit).
 */
export async function claimPort(preferred, {
  dir = path.join(os.tmpdir(), 'ipass-port-claims'), isFreeFn = isFree, ephemeral = ephemeralPort, alive = pidAlive, pid = process.pid, attempts = 20
} = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const take = (port) => {
    const claim = path.join(dir, String(port));
    for (let tries = 0; tries < 2; tries += 1) {
      try {
        fs.mkdirSync(claim);
        fs.writeFileSync(path.join(claim, 'owner'), `${pid}\n`);
        return claim;
      } catch (e) {
        if (e.code !== 'EEXIST') throw e;
        let owner = NaN;
        try { owner = Number(fs.readFileSync(path.join(claim, 'owner'), 'utf8').trim()); } catch { /* being written */ }
        if (Number.isInteger(owner) && !alive(owner)) fs.rmSync(claim, { recursive: true, force: true });
        else return null;
      }
    }
    return null;
  };
  const candidates = Number.isInteger(preferred) && preferred > 0 && preferred < 65536 ? [preferred] : [];
  for (let i = 0; i < attempts; i += 1) {
    const port = candidates.length > 0 ? candidates.shift() : await ephemeral();
    if (!(await isFreeFn(port))) continue;
    const claim = take(port);
    if (claim === null) continue;
    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      try {
        if (fs.readFileSync(path.join(claim, 'owner'), 'utf8').trim() === String(pid)) fs.rmSync(claim, { recursive: true, force: true });
      } catch { /* already gone */ }
    };
    process.once('exit', release);
    return { port, release };
  }
  throw new Error(`claimPort: no free port after ${attempts} tries (preferred ${preferred})`);
}

/**
 * The dev-only exception port (IPASS_BS_EXCEPTION_PORT) is also WireMock's published host port. When the configured
 * port is busy or claimed by an overlapping run, a free one replaces it in `env` BEFORE settings are read, so the app's
 * allow-list and the specs follow. Not configured / malformed: untouched (settings.mjs fails closed as before).
 */
export async function claimExceptionPort(env, claim = claimPort) {
  const raw = (env.IPASS_BS_EXCEPTION_PORT ?? '').trim();
  if (!/^[0-9]{1,5}$/.test(raw) || Number(raw) < 1 || Number(raw) > 65535) return null;
  const claimed = await claim(Number(raw));
  env.IPASS_BS_EXCEPTION_PORT = String(claimed.port);
  return claimed;
}

/** Container name of the gate's Artemis: unique per run so a second gate never removes or reuses it. */
export const artemisName = (id) => `t174c-artemis-${id}`;
