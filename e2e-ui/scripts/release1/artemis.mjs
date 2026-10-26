// T174b: the Release 1 gate's one Artemis broker (the compose e2e profile's image, E2eImages.ARTEMIS), started by the
// runner INSIDE its docker slot, published on loopback only, with a login generated per run (memory only). Anonymous
// login stays on so the MQ-CSV target, which has no queue-manager password configured, can connect; the port is not
// reachable from the LAN. The queue is created up front so the scenario can read its message count before sending.
import { spawnSync } from 'node:child_process';

export const ARTEMIS_IMAGE = 'apache/activemq-artemis:2.44.0@sha256:0305c26f19eda00c0506d0953cfe265600a72ac1b17f7934412120c01a595cff';
export const ARTEMIS_CLI = '/var/lib/artemis-instance/bin/artemis';

/** `docker run` arguments: loopback-only publish, login from env names, no value on the command line. */
export function runArgs({ name, port, image = ARTEMIS_IMAGE }) {
  if (!/^[a-z0-9][a-z0-9_.-]{0,62}$/.test(name)) throw new Error(`artemis: container name ${name} is not plain`);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`artemis: port ${port} is not a TCP port`);
  return ['run', '-d', '--name', name, '-p', `127.0.0.1:${port}:61616`,
    '-e', 'ARTEMIS_USER', '-e', 'ARTEMIS_PASSWORD', '-e', 'ANONYMOUS_LOGIN=true', '-e', 'TZ=Etc/UTC', image];
}

function docker(args, input) {
  return spawnSync('docker', args, { encoding: 'utf8', timeout: 120000, env: input });
}

/** Starts the broker, waits for `check node --up`, creates `queue`. Returns { stop }. */
export async function startArtemis({ name, port, queue, user, password, base }) {
  const env = { ...base, ARTEMIS_USER: user, ARTEMIS_PASSWORD: password };
  const run = docker(runArgs({ name, port }), env);
  if (run.status !== 0) throw new Error(`artemis: docker run failed: ${run.stderr?.trim().slice(0, 300)}`);
  const stop = () => { docker(['rm', '-f', name], base); };
  try {
    const check = `${ARTEMIS_CLI} check node --up --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD"`;
    let ready = false;
    for (let i = 0; i < 40 && !ready; i += 1) {
      ready = docker(['exec', name, 'sh', '-c', check], base).status === 0;
      if (!ready) await new Promise((resolve) => setTimeout(resolve, 3000));
    }
    if (!ready) throw new Error('artemis: broker not up after 120 s');
    const create = docker(['exec', name, 'sh', '-c', `${ARTEMIS_CLI} queue create --name ${queue} --address ${queue} --anycast --auto-create-address --durable --silent --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD"`], base);
    if (create.status !== 0) throw new Error(`artemis: queue create failed: ${create.stderr?.trim().slice(0, 300)}`);
    return { stop };
  } catch (e) {
    stop();
    throw e;
  }
}
