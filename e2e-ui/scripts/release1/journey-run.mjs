// L28 (row 372, review V4 R1/R2/V1): the browser journey runner's pure, offline-testable parts.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE_MQ_PORT = 61616;

/**
 * R2: every name that two concurrent runners could collide on is derived from the slot this run holds
 * (.docker-suite.lock-2 -> 2). The unnumbered slot is 0. Two runners hold two different slots, so they get
 * different Artemis containers, MQ ports and inner locks.
 */
export function journeyNames(slotPath, tmp = os.tmpdir()) {
  const match = /-(\d+)$/.exec(path.basename(slotPath));
  const n = match ? Number(match[1]) : 0;
  return { n, artemisName: `l28-artemis-${n}`, mqPort: BASE_MQ_PORT + n, innerLock: path.join(tmp, `l28-inner-${n}.lock`) };
}

/**
 * R1: one idempotent cleanup. Steps run newest-first (console, backend, Artemis, slot), each in its own
 * try/catch so one failure never strands the rest. A second call (signal during finally) is a no-op.
 */
export function makeCleanup(log = () => {}) {
  const steps = [];
  let done = null;
  return {
    add(name, fn) { steps.push([name, fn]); },
    run() {
      done ??= (async () => {
        for (const [name, fn] of [...steps].reverse()) {
          try { await fn(); } catch (e) { log(`cleanup ${name}: ${e.message}`); }
        }
      })();
      return done;
    }
  };
}

/** R1: SIGINT / SIGTERM run the cleanup, then exit 130 / 143. Returns a function that removes the handlers. */
export function installSignalCleanup(cleanup, { proc = process, exit = (code) => process.exit(code), log = () => {} } = {}) {
  const handlers = Object.entries({ SIGINT: 130, SIGTERM: 143 }).map(([signal, code]) => {
    const handler = () => { log(`${signal} received: cleaning up`); cleanup.run().finally(() => exit(code)); };
    proc.on(signal, handler);
    return [signal, handler];
  });
  return () => handlers.forEach(([signal, handler]) => proc.off(signal, handler));
}

/** V1: Playwright's json reporter stats, or null when the report is missing or unreadable. */
export function readStats(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')).stats ?? null;
  } catch {
    return null;
  }
}
