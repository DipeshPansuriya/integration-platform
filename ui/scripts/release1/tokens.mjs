// T174b: the bearer tokens the Java Release 1 gate reads from E2E_BEARER_<ROLE> (Release1Client.bearer). Names and SQL
// only; every secret lives in the caller's memory. Admin roles are Keycloak service-account clients whose subject is
// bound to a tenant as a user with one role, the way the Java suites' adminUserToken does; CUSTOMER_A is the tenant's
// own source credential (client credentials), issued through the admin API.

const ROLES = Object.freeze(['TENANT_ADMIN', 'APPROVER', 'CONFIGURATOR', 'READ_ONLY']);

const quote = (text) => `'${text}'`;

/** The two inserts that make `subject` (issuer-scoped) a user of tenant `code` holding `role`. */
export function bindSubjectSql({ tenantCode, issuer, subject, role }) {
  if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,63}$/.test(tenantCode ?? '')) throw new Error(`bindSubjectSql: tenant code ${tenantCode} is not a plain code`);
  if (!/^https?:\/\/[A-Za-z0-9.:/_-]+$/.test(issuer ?? '')) throw new Error('bindSubjectSql: issuer is not a plain URL');
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(subject ?? '')) throw new Error('bindSubjectSql: subject is not a uuid');
  if (!ROLES.includes(role)) throw new Error(`bindSubjectSql: role ${role} is not one of ${ROLES.join(', ')}`);
  return [
    `INSERT INTO public.app_user (tenant_id, issuer, subject, status) SELECT id, ${quote(issuer)}, ${quote(subject)}, 'ACTIVE' FROM public.tenant WHERE code=${quote(tenantCode)}`,
    `INSERT INTO public.role_grant (tenant_id, user_id, role) SELECT tenant_id, id, ${quote(role)} FROM public.app_user WHERE issuer=${quote(issuer)} AND subject=${quote(subject)}`
  ];
}

/** The environment names of the Java gate for a { ROLE: token } map; refuses a blank token. */
export function bearerEnv(tokens) {
  const out = {};
  for (const [role, token] of Object.entries(tokens)) {
    if (typeof token !== 'string' || token.length < 20) throw new Error(`bearerEnv: no token for ${role}`);
    out[`E2E_BEARER_${role}`] = token;
  }
  return out;
}

/** A client-credentials access token at the realm's token endpoint; the failure names the client, never the secret. */
export async function clientCredentialsToken(issuer, clientId, clientSecret, fetchImpl = fetch) {
  const answer = await fetchImpl(`${issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: clientId, client_secret: clientSecret }),
    signal: AbortSignal.timeout(10000)
  });
  const data = await answer.json().catch(() => null);
  if (answer.status !== 200 || typeof data?.access_token !== 'string') {
    throw new Error(`token for ${clientId}: HTTP ${answer.status} ${data?.error ?? ''}`);
  }
  return data.access_token;
}
