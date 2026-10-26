// L22: the postgres container of THIS screens run. global-setup publishes it as IPASS_UI_PG_CONTAINER (workers inherit it);
// without it (a spec run against a hand-booted suite) the single running ipass-ui-t41-pg-* is used, as before.
import { execFileSync } from 'node:child_process';
import { PG_ENV, pickSuitePg } from '../../scripts/lib/isolation.mjs';

export function suitePgContainer(env = process.env) {
  if (env[PG_ENV]) return pickSuitePg(env, []);
  const running = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' }).split('\n');
  return pickSuitePg(env, running);
}
