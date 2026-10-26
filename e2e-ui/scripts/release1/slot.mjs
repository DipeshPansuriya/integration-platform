// T174b: the docker-suite slot protocol for the Release 1 gate runner. The four slots are directories beside the
// code root's parent: .docker-suite.lock, .docker-suite.lock-1, -2, -3 (mkdir = take, owner file = who). A slot held
// by anyone else is NEVER removed, stolen or aged out here; the runner polls and waits. The owner note is refreshed
// while we hold it, because other lock takers treat a note older than 60 minutes as stale.
import fs from 'node:fs';
import path from 'node:path';

export const SLOT_NAMES = Object.freeze(['.docker-suite.lock', '.docker-suite.lock-1', '.docker-suite.lock-2', '.docker-suite.lock-3']);

const ownerFile = (dir) => path.join(dir, 'owner');

function ownerOf(dir) {
  try {
    return fs.readFileSync(ownerFile(dir), 'utf8').trim();
  } catch {
    return '(no owner note)';
  }
}

/**
 * Takes the first free slot, polling every `pollMs` until `maxWaitMs`. Returns { path, release, refresh }.
 * `sleep` and `log` are injectable for the unit test.
 */
export async function acquireSlot({
  root, holder, slotNames = SLOT_NAMES, pollMs = 60000, maxWaitMs = 4 * 60 * 60 * 1000,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)), now = () => Date.now(), log = () => {}
}) {
  if (typeof holder !== 'string' || holder.trim() === '') throw new Error('acquireSlot: name the holder');
  const started = now();
  for (;;) {
    const held = [];
    for (const name of slotNames) {
      const dir = path.join(root, name);
      try {
        fs.mkdirSync(dir);
      } catch (e) {
        if (e.code !== 'EEXIST') throw e;
        held.push(`${name}: ${ownerOf(dir)}`);
        continue;
      }
      const note = () => fs.writeFileSync(ownerFile(dir), `${holder} ${new Date().toISOString()}\n`, { flag: 'w' });
      note();
      log(`took ${name} as "${holder}"`);
      return {
        path: dir,
        refresh: note,
        release() {
          // only our own directory: the owner note must still start with our holder
          if (ownerOf(dir).startsWith(holder)) fs.rmSync(dir, { recursive: true, force: true });
        }
      };
    }
    if (now() - started > maxWaitMs) throw new Error(`acquireSlot: no docker slot free within ${maxWaitMs} ms; held: ${held.join(' | ')}`);
    log(`all slots held (${held.join(' | ')}); polling again in ${pollMs} ms`);
    await sleep(pollMs);
  }
}
