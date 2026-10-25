// T214: registers NESTED-APPOINTMENT on a running iPass (the hosted showcase) through the admin API, logging in as two
// real realm users of tenant showcase-a (four-eyes: an author and a DIFFERENT approver). Run by the owner, from a machine that
// can reach the app.
// Usage:  node scripts/seed-nested-appointment-hosted.mjs [--print-plan]
// Reads env NAMES only (values come from the OS store / password manager, never from a file or the command line):
//   IPASS_BASE_URL (https://ipass.<domain>)
//   SEED_AUTHOR_USER=showcase-admin-a (TENANT_ADMIN: edits, submits, publishes AND issues the source credential - a
//     CONFIGURATOR cannot publish or issue one, RolePolicy.java)   SEED_AUTHOR_PASSWORD
//   SEED_APPROVER_USER=showcase-approver-a (APPROVER: approves the two contracts and the flow)   SEED_APPROVER_PASSWORD
//   SEED_TARGET_BASE   SEED_SOURCE_BASE   [SEED_CUSTOMER_ID]
// Prints the new source credential ONCE on stdout when it issues one (do not redirect it to a file). Never run from CI or a chat.
// Every request is https-only, restricted to the app's own domain, and has a timeout (hosted-guards.mjs); a failure prints one
// short line - no stack, no secret.
import { describeFailure, guardedFetch } from './release1/hosted-guards.mjs';

const NAMES = ['IPASS_BASE_URL', 'SEED_AUTHOR_USER', 'SEED_AUTHOR_PASSWORD', 'SEED_APPROVER_USER', 'SEED_APPROVER_PASSWORD', 'SEED_TARGET_BASE', 'SEED_SOURCE_BASE'];

if (process.argv.includes('--print-plan')) {
  console.log('seed-nested-appointment-hosted: login author (showcase-admin-a) + approver (showcase-approver-a) via the realm form -> list flows -> [skip if NESTED-APPOINTMENT is PUBLISHED] -> ensure systems + contracts (same content or stop) -> flow, mapping, sample run, submit, approve, publish -> issue source credential');
  console.log(`env names: ${NAMES.join(', ')} [SEED_CUSTOMER_ID]`);
  process.exit(0);
}

const missing = NAMES.filter((name) => !process.env[name]);
if (missing.length) {
  console.error(`seed-nested-appointment-hosted: missing env names: ${missing.join(', ')}`);
  process.exit(2);
}
const base = process.env.IPASS_BASE_URL.replace(/\/+$/, '');
if (!base.startsWith('https://')) {
  console.error('seed-nested-appointment-hosted: IPASS_BASE_URL must be https://');
  process.exit(2);
}
const secrets = [process.env.SEED_AUTHOR_PASSWORD, process.env.SEED_APPROVER_PASSWORD];

try {
  globalThis.fetch = guardedFetch(base, globalThis.fetch); // the login's own hops (realm form, code callback) go through it too
  const { sessionHeaders, sessionLogin } = await import('./keycloak-harness.mjs');
  const { seedNestedAppointment } = await import('./release1/seed-nested-appointment.mjs');
  const sessions = {
    default: await sessionLogin(base, { username: process.env.SEED_AUTHOR_USER, password: process.env.SEED_AUTHOR_PASSWORD }),
    approver: await sessionLogin(base, { username: process.env.SEED_APPROVER_USER, password: process.env.SEED_APPROVER_PASSWORD })
  };
  for (const [login, session] of Object.entries(sessions)) {
    if (session.status !== 200) throw new Error(`the ${login} session's first call answered ${session.status}`);
  }

  const call = async (login, method, apiPath, body) => {
    const response = await fetch(new URL(apiPath, base), {
      method,
      headers: { ...sessionHeaders(sessions[login], method), ...(body ? { 'content-type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined
    });
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return { status: response.status, data };
  };
  const seeded = await seedNestedAppointment({
    call, customerId: process.env.SEED_CUSTOMER_ID ?? 'showcase-nested-customer',
    apiBase: process.env.SEED_TARGET_BASE, sourceBase: process.env.SEED_SOURCE_BASE
  });
  console.log(`NESTED-APPOINTMENT ${seeded.skipped ? 'already published (nothing written)' : seeded.resumed ? 'resumed and published' : 'seeded'}: flow ${seeded.flowId}, interface code ${seeded.interfaceCode}`);
  if (seeded.credential) console.log(`source credential (shown once - put it in the password manager now): client id ${seeded.credential.clientId}, secret ${seeded.credential.secret}`);
} catch (failure) {
  console.error(describeFailure(failure, secrets));
  process.exit(1);
}
