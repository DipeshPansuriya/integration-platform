// L22: offline tests for scripts/lib/isolation.mjs - no Docker, no listening on a fixed port.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  artemisName, claimExceptionPort, claimPort, lockCandidates, pickSuitePg, runId, slotIndex
} from '../../../scripts/lib/isolation.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'l22-iso-'));
const always = async () => true;

test('slotIndex reads the slot number, 0 for the first slot and for a private lock', () => {
  assert.equal(slotIndex('D:/P/.docker-suite.lock'), 0);
  assert.equal(slotIndex('D:/P/.docker-suite.lock-3'), 3);
  assert.equal(slotIndex('C:/tmp/t174c-inner-0a1b2c3d.lock'), 0);
});

test('lockCandidates: the four slots in order, or only DOCKER_SUITE_LOCK when the caller set it', () => {
  const all = lockCandidates({}, path.join('X', 'P', '.docker-suite.lock')).map((p) => path.basename(p));
  assert.deepEqual(all, ['.docker-suite.lock', '.docker-suite.lock-1', '.docker-suite.lock-2', '.docker-suite.lock-3']);
  assert.deepEqual(lockCandidates({ DOCKER_SUITE_LOCK: 'Y/own' }, 'X/.docker-suite.lock'), ['Y/own']);
  assert.equal(lockCandidates({ DOCKER_SUITE_LOCK: '' }, 'X/.docker-suite.lock').length, 4);
});

test('pickSuitePg: the published name wins even when two suite postgres run; the scan needs exactly one', () => {
  const a = 'ipass-ui-t41-pg-0a1b2c3d';
  const b = 'ipass-ui-t41-pg-ffffffff';
  assert.equal(pickSuitePg({ IPASS_UI_PG_CONTAINER: a }, [a, b]), a);
  assert.equal(pickSuitePg({}, ['other', `${a}\r`]), a);
  assert.throws(() => pickSuitePg({}, [a, b]), /exactly one booted suite postgres expected/);
  assert.throws(() => pickSuitePg({}, []), /exactly one/);
  assert.throws(() => pickSuitePg({ IPASS_UI_PG_CONTAINER: 'postgres' }, []), /not a suite postgres name/);
  assert.throws(() => pickSuitePg({ IPASS_UI_PG_CONTAINER: `${a}; ls` }, []), /not a suite postgres name/);
});

test('runId and artemisName: 8 hex, unique names per run', () => {
  assert.match(runId(), /^[0-9a-f]{8}$/);
  assert.notEqual(runId(), runId());
  assert.equal(artemisName('0a1b2c3d'), 't174c-artemis-0a1b2c3d');
});

test('claimPort: preferred when free, a different port for an overlapping claim, reuse after release', async () => {
  const dir = tmp();
  const opts = { dir, isFreeFn: always, ephemeral: (() => { let n = 50000; return async () => n++; })() };
  const first = await claimPort(8089, opts);
  const second = await claimPort(8089, opts);
  assert.equal(first.port, 8089);
  assert.equal(second.port, 50000);
  first.release();
  const third = await claimPort(8089, opts);
  assert.equal(third.port, 8089);
  [second, third].forEach((c) => c.release());
  assert.deepEqual(fs.readdirSync(dir), []);
});

test('claimPort: a port that is bound is skipped; a dead pid reclaims, a live pid never loses its claim', async () => {
  const dir = tmp();
  const busy = new Set([61616]);
  const opts = { dir, isFreeFn: async (p) => !busy.has(p), ephemeral: (() => { let n = 50100; return async () => n++; })() };
  assert.equal((await claimPort(61616, opts)).port, 50100);
  fs.mkdirSync(path.join(dir, '4173'));
  fs.writeFileSync(path.join(dir, '4173', 'owner'), '999999\n');
  const dead = await claimPort(4173, { ...opts, alive: () => false });
  assert.equal(dead.port, 4173, 'a claim of a dead pid is taken over');
  const live = await claimPort(4173, { ...opts, alive: () => true, pid: 111 });
  assert.notEqual(live.port, 4173, 'a live holder keeps its claim');
  live.release();
  assert.ok(fs.existsSync(path.join(dir, '4173')), 'release removes only our own claim');
});

test('claimPort: no free port at all fails loudly', async () => {
  await assert.rejects(claimPort(8089, { dir: tmp(), isFreeFn: async () => false, ephemeral: async () => 50200, attempts: 3 }), /no free port/);
});

test('claimExceptionPort swaps a busy port into env and leaves an unset or malformed one for settings.mjs to refuse', async () => {
  const env = { IPASS_BS_EXCEPTION_PORT: ' 8089 ' };
  const claimed = await claimExceptionPort(env, async (p) => ({ port: p + 1, release() {} }));
  assert.equal(env.IPASS_BS_EXCEPTION_PORT, '8090');
  assert.equal(claimed.port, 8090);
  for (const value of [undefined, '', 'abc', '0', '70000']) {
    const other = value === undefined ? {} : { IPASS_BS_EXCEPTION_PORT: value };
    assert.equal(await claimExceptionPort(other, async () => { throw new Error('must not claim'); }), null);
    assert.equal(other.IPASS_BS_EXCEPTION_PORT, value);
  }
});
