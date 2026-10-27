// F3 (row 523, ADR-H-IPASS-03): the worker's access to the target database sidecar the boot started
// (BS_TARGET_DB_CONTAINER / BS_TARGET_DB_PORT name it - names only; the passwords here are random and live in this
// worker's memory). One call creates a tenant's narrow login; its password is then stored through the real
// credentials API (the spec), never written anywhere else.

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import { accountName, narrowAccountSql, targetPsqlArgs } from '../lib/target-db.mjs';
import { bsEnv } from './boot-state.mjs';

/** The port the target database is published on (the DB systems of the f3 spec point at it). */
export const targetDbPort = () => Number(bsEnv('targetDbPort'));

/** Creates the tenant's narrow login on the sidecar (statements on stdin, bounded 30 s); returns `{ account, password }`. */
export function createTargetAccount(key) {
  const container = bsEnv('targetDbContainer');
  const account = accountName(key, container.slice(-8));
  const password = crypto.randomBytes(18).toString('base64url');
  execFileSync('docker', targetPsqlArgs(container), { input: `${narrowAccountSql({ account, password }).join(';\n')};\n`, encoding: 'utf8', timeout: 30000 });
  return { account, password };
}
