// T214: the hosted driver of the NESTED-APPOINTMENT seed only reads env NAMES, plans without touching anything, and refuses to
// start with a missing name - these tests never reach a network.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const driver = new URL('../../../scripts/seed-nested-appointment-hosted.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const run = (args, env) => spawnSync(process.execPath, [driver, ...args], { encoding: 'utf8', env: { PATH: process.env.PATH, ...env } });

test('--print-plan lists the steps and the env names and starts nothing', () => {
  const out = run(['--print-plan'], { SEED_AUTHOR_PASSWORD: 'must-not-be-printed' });
  assert.equal(out.status, 0, out.stderr);
  for (const name of ['IPASS_BASE_URL', 'SEED_AUTHOR_USER', 'SEED_AUTHOR_PASSWORD', 'SEED_APPROVER_USER', 'SEED_APPROVER_PASSWORD', 'SEED_TARGET_BASE', 'SEED_SOURCE_BASE']) {
    assert.ok(out.stdout.includes(name), name);
  }
  assert.ok(!out.stdout.includes('must-not-be-printed'));
});

test('a missing name stops the run before any call, naming it', () => {
  const out = run([], { IPASS_BASE_URL: 'https://ipass.invalid' });
  assert.notEqual(out.status, 0);
  assert.match(out.stderr, /SEED_AUTHOR_PASSWORD/);
  assert.ok(!out.stderr.includes('ipass.invalid/'), 'no request was made');
});

test('the hosted base must be https', () => {
  const env = { IPASS_BASE_URL: 'http://ipass.invalid', SEED_AUTHOR_USER: 'a', SEED_AUTHOR_PASSWORD: 'b', SEED_APPROVER_USER: 'c', SEED_APPROVER_PASSWORD: 'd', SEED_TARGET_BASE: 'http://wiremock:8089/nested-target/', SEED_SOURCE_BASE: 'http://wiremock:8089/nested-source/' };
  const out = run([], env);
  assert.notEqual(out.status, 0);
  assert.match(out.stderr, /https/);
});

// B1: the roles named for the hosted run must be able to do what the seed does. The roles come from the code (RolePolicy.java),
// the users and their roles from the hosted seed (seed-showcase.sh + seed.sql) - this test reads all of them.
const text = (rel) => readFileSync(new URL(`../../../../${rel}`, import.meta.url), 'utf8');
const policy = text('platform-tenancy/src/main/java/io/integrationplatform/tenancy/RolePolicy.java');
const rolesOf = (operation) => new RegExp(String.raw`${operation}\(Caller\.ROLE, ([A-Z_, ]+)\)`).exec(policy)[1].split(',').map((r) => r.trim());
const seedScript = text('deploy/hostinger/seed/seed-showcase.sh');
const seedSql = text('deploy/hostinger/seed/seed.sql');
const hostedRole = (user) => {
  const variable = new RegExp(String.raw`(sub_[a-z_]+)="\$\(ensure_user ${user} `).exec(seedScript)?.[1];
  assert.ok(variable, `${user} is not created by seed-showcase.sh`);
  const role = new RegExp(String.raw`:'${variable}',\s*'([A-Z_]+)'`).exec(seedSql)?.[1];
  assert.ok(role, `${user} has no role in seed.sql`);
  return role;
};
const doc = text('deploy/hostinger/seed/NESTED-APPOINTMENT.md');
const driverSource = readFileSync(new URL('../../../scripts/seed-nested-appointment-hosted.mjs', import.meta.url), 'utf8');
const namedUser = (source, name) => new RegExp(`${name}=(showcase-[a-z]+-[ab])`).exec(source)?.[1];

test('B1: the author the runbook and the driver name can edit, submit, publish and issue the source credential', () => {
  for (const [label, source] of [['runbook', doc], ['driver', driverSource]]) {
    const author = namedUser(source, 'SEED_AUTHOR_USER');
    assert.ok(author, `${label} names no SEED_AUTHOR_USER`);
    const role = hostedRole(author);
    for (const operation of ['EDIT_CONFIGURATION', 'SUBMIT_FOR_APPROVAL', 'PUBLISH_VERSION', 'ISSUE_SOURCE_CREDENTIAL']) {
      assert.ok(rolesOf(operation).includes(role), `${label}: ${author} holds ${role}, which cannot ${operation}`);
    }
  }
});

test('B1: the approver can approve, and is a different user from the author', () => {
  for (const [label, source] of [['runbook', doc], ['driver', driverSource]]) {
    const approver = namedUser(source, 'SEED_APPROVER_USER');
    assert.ok(approver, `${label} names no SEED_APPROVER_USER`);
    assert.ok(rolesOf('APPROVE_VERSION').includes(hostedRole(approver)), `${label}: ${approver} cannot approve`);
    assert.notEqual(approver, namedUser(source, 'SEED_AUTHOR_USER'));
  }
});

test('B1: no file names the configurator as the author or as a fallback', () => {
  assert.ok(!/showcase-configurator/.test(doc), 'runbook');
  assert.ok(!/showcase-configurator/.test(driverSource), 'driver');
});
