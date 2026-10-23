// B35 (row 372) - FAILING FIRST. No docker: pure.
//
// The booted app starts db-scheduler, which at its own startup registers the closed job register in
// scheduler.scheduled_tasks. The app login can only touch schema `scheduler` once it is a member of the NOLOGIN
// role ipaas_app (V2_2_1400 grants USAGE to ipaas_app only). The harness used to grant that membership AFTER the app
// was healthy, so every boot logged "permission denied for schema scheduler" ("Unexpected error while executing
// OnStartup tasks. Continuing.") and the register stayed empty. A deployment grants the role before the app starts
// (platform-app GrantAppRoleFlywayCallback.java, E18Base.java GrantAppRole): the harness now does the same, and a
// scheduler startup failure that still happens fails the boot instead of hiding in the app log.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { appRoleBootSql, schedulerStartupFailure } from '../../../scripts/boot-backend.mjs';

test('the role is pre-created exactly as V1_3_1 creates it, then granted to the login, before the app starts', () => {
  const sql = appRoleBootSql('console_login_ab12');
  assert.equal(sql.length, 2);
  // V1_3_1 refuses any ipaas_app with login, superuser, bypassrls, createdb, createrole or replication: stay inside it
  assert.match(sql[0], /CREATE ROLE ipaas_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION/);
  assert.match(sql[0], /IF NOT EXISTS|NOT EXISTS|WHERE NOT EXISTS|pg_roles/i, 'idempotent: a second boot on the same database must not fail');
  assert.equal(sql[1], 'GRANT ipaas_app TO console_login_ab12');
});

test('a login name that is not a plain identifier is refused (no SQL built from it)', () => {
  for (const bad of ['x; drop table t', 'a b', '', undefined, 'Robert"--']) {
    assert.throws(() => appRoleBootSql(bad), /login/, String(bad));
  }
});

test('the scheduler startup failure the harness used to hide is found in the app log', () => {
  const log = [
    '{"message":"Migrating schema \\"public\\" to version \\"2.4.1\\""}',
    '{"message":"Unexpected error while executing OnStartup tasks. Continuing.","logger_name":"com.github.kagkarlsson.scheduler.Scheduler","level":"ERROR","stack_trace":"PSQLException: ERROR: permission denied for schema scheduler"}'
  ];
  assert.match(schedulerStartupFailure(log), /OnStartup/);
  assert.equal(schedulerStartupFailure(['{"message":"Started Application"}']), null);
  assert.equal(schedulerStartupFailure([]), null);
});
