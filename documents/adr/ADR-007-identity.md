---
type: ADR
title: "ADR-007 - Identity: Keycloak for login and machine credentials, tenant bound from our database, target credentials encrypted with an out-of-database key"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-009-tenant-directory-rls.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-010-camel-routing-core.md
  - ../rule.md
---

# ADR-007 - Identity: Keycloak for login and machine credentials, tenant bound from our database, target credentials encrypted with an out-of-database key

New record (no earlier ADR-007 file existed; earlier work deferred three questions to it - see
Context). Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id is the stable key.

## Context

*Sources: `ws/_specs/project-plan.md:85`, `:109`, `:110`, `:138`; `ws/_memory/shared.md:25`
(m-703833); `:111` (m-a474d8), `:113` (m-6c7ae2), `:118` (m-ad7c6c);
`ws/_specs/f2-tenant-directory-rls-migration-draft.md:101-102`, `:152-153`;
`ws/_specs/f3-plan-brief.md:39`, `:75`.*

An end user logs in to the console and sees only their tenant's configuration. A source system
calls the common API with its own machine credential, and the customer is identified from that
credential, never from a `customerId` in the body. Roles (tenant admin, configurator, support,
read-only) are enforced server-side. Target systems need credentials (API keys, passwords,
certificates) that the platform stores and uses at send time.

Three questions were explicitly deferred to this ADR by earlier work:

| Deferred question | Where it was deferred |
|---|---|
| Real identity-provider signature validation (the earlier build used a stub decoder in its suites) | `f2-...-draft.md:101-102`; m-ad7c6c |
| "GUC residue": the application database role can call `set_config` on its own session setting `app.tenant_id` | `f2-...-draft.md:152-153`; m-a474d8, m-6c7ae2 ("stays with ADR-007") |
| The key that encrypts target credentials must live outside the database | `ws/_specs/project-plan.md:85`, `:110` |

The deleted design stored target credentials in OpenBao and kept only a reference path in the
`target_endpoint` row (deleted data-design.md:179 and workflows.md:248, quoted at
`ws/_specs/f3-plan-brief.md:39`, `:75`).

**Reading taken (for Dipesh's confirmation):** "customer" in his example is the party behind a
registered source system; the tenant is the platform account that owns that system. A
credential therefore resolves to one source system, which belongs to one tenant and carries one
customer identity.

## Decision 1 - Keycloak, standard OIDC only

- **Keycloak** (Apache-2.0) is the identity provider. The platform uses standard OpenID Connect
  and OAuth 2.0 only - Spring Security's OAuth2 resource server and OAuth2 client - and no
  Keycloak-specific adapter (`ws/_specs/project-plan.md:85`). Any compliant provider can replace
  it by configuration.
- **Version: UNVERIFIED.** The exact Keycloak release and image tag are pinned before the login
  phase starts, from neha-research's answer (`ws/_specs/project-plan.md:217`). The skeleton
  phase does not need Keycloak.
- Keycloak runs on its own database (a separate database and database user; it may share the
  PostgreSQL server in development).

### Realm model

| Option | For | Against | Verdict |
|---|---|---|---|
| **One platform realm; tenants are NOT modelled in Keycloak** | One place to operate; the tenant binding stays in our row-level-secured database (ADR-009) | User administration touches two systems (Keycloak user, platform grant) | **Chosen** |
| One realm per tenant | Strong separation inside the identity provider | Many realms to configure and upgrade; tenant truth in two places | Rejected |
| One realm with Keycloak Organizations or groups carrying the tenant | Tenant visible in tokens | A second source of tenant truth that a misconfigured mapper could forge | Rejected |

Self-registration is disabled (tenants are onboarded by the platform operator -
`ws/_specs/project-plan.md:230`).

### Console login (humans)

- Authorization Code flow with PKCE, with the **platform as a confidential client (backend for
  frontend)**: the browser holds only a session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`)
  and never an access token; state-changing requests carry CSRF protection. This is the pattern
  the IETF's browser-based-apps guidance recommends.
- Server sessions are stored in PostgreSQL (Spring Session JDBC), so instances stay stateless.
- Multi-factor authentication is supported by the realm; **whether it is mandatory is
  Dipesh's business rule** (recommendation: mandatory for tenant admins).

## Decision 2 - The tenant is bound from our database, never from a token claim

- **Humans:** the pair (issuer, subject) of a validated token is looked up through the one
  trusted login bootstrap function of ADR-009, which returns the user and tenant only for
  active users of active tenants. A `tenant` claim, if a token carries one, is only cross-checked:
  a mismatch is denied and audited.
- **Source systems:** the pair (issuer, client id from the `azp` claim) is looked up through a
  second narrow definer function of the same shape, `ipaas_client_bootstrap`, returning the
  credential status, source system, customer identity and tenant for active credentials only.
- **Body cross-check:** a `customerId` in the request body is compared with the customer
  identity of the calling source system; a mismatch is rejected (403) and audited, never
  trusted (`ws/_specs/project-plan.md:138`).
- **Conformance:** a forged tenant claim, a forged tenant header and a forged body `customerId`
  each change nothing (tests in the login phase; the cross-tenant denial suite re-passes).

## Decision 3 - Machine credentials for source systems

- One source-system credential = **one confidential Keycloak client** using the client
  credentials grant. Access-token lifetime 5 minutes.
- Client authentication: client secret by default; `private_key_jwt` when the source supports
  it.
- **Issue, rotate, revoke** are tenant-admin actions in the console. The platform calls the
  Keycloak Admin REST API with a service account holding only the client-management role of the
  platform realm.
  - The secret is shown once at creation and never stored, returned again or logged by the
    platform.
  - **Rotation = issue a second credential**, both valid during a grace window (default 7 days),
    then revoke the old one. This needs no provider-specific rotation feature.
  - **Revocation is immediate:** the client is disabled in Keycloak and the credential row is
    marked revoked; because every call resolves the tenant through the bootstrap function, which
    accepts only active credentials, a still-unexpired token of a revoked credential is refused.
- Credential creation is ordered so nothing is half-created: the row is written as pending,
  the Keycloak client is created, the row becomes active; a pending row older than 15 minutes
  is reconciled by a job.

## Decision 4 - Token validation

- Issuer must equal the configured issuer; signature verified against the issuer's JWKS
  (cached, refreshed on unknown key id); algorithms allow-listed to RS256 and ES256 (never
  `none`, never HMAC); `aud` must contain the platform API audience; `exp` and `nbf` checked with
  at most 60 seconds of clock skew.
- 401 and 403 are final: no layer of the platform retries them.
- Every call to Keycloak (JWKS fetch, Admin API) carries the five controls of the
  resilient-integration rule: timeout (2 s JWKS, 5 s admin), retry of transient failures of
  idempotent reads only, one circuit breaker for the provider, a fallback (cached keys keep
  validating; a credential action fails with a clear error and nothing half-created), metrics
  and a trace span.

## Decision 5 - Roles live in the platform, per tenant

- Roles are rows in the tenant-scoped, row-level-secured `role_grant` table, not Keycloak roles:
  `TENANT_ADMIN`, `CONFIGURATOR`, `APPROVER`, `SUPPORT`, `READ_ONLY`.
- `APPROVER` is added to the plan's four roles because a new configuration or contract version
  needs an approver who is not its author (`ws/_specs/project-plan.md:105`, `:110`; the deleted
  API contract had an approver role, quoted at `ws/_specs/f3-plan-brief.md:555`). Tenant admins
  may also approve. Approver-is-never-author is enforced on the record by a database `CHECK`,
  not by the role.
- Enforced server-side on every endpoint, with a failing test per role written first.

## Decision 6 - The session setting is stamped per transaction (the "GUC residue")

| Option | For | Against | Verdict |
|---|---|---|---|
| **A. Transaction-scoped stamping + compensating controls** | Standard; no per-row cost; a forgotten reset cannot leak across pooled connections | The application role can still change the setting inside its own transaction (only via SQL it sends) | **Chosen** |
| B. Signed setting: the value carries an HMAC verified by a definer function inside every policy | The application role cannot forge another tenant | A function call in every policy; key handling inside the database; more moving parts | Upgrade path |
| C. A database role per tenant | Isolation by grant | One pool per tenant; does not scale with tenants | Rejected |

Rules for option A (each MUST has a conformance test):

1. The tenant setting is stamped with `set_config('app.tenant_id', <bound tenant>, true)` -
   transaction-local - at the start of every transaction, from the tenant bound by Decision 2
   or by ADR-011's background resolver. It is never stamped at session scope.
2. Every tenant-scoped access runs inside a transaction. A statement outside one sees an empty
   setting and fails closed (ADR-009's error-deny form). Test: an autocommit select on a tenant
   table errors.
3. Every SQL statement is parameterised; no string-built SQL. Test: a static-analysis step in
   the build fails on a JDBC or JdbcTemplate call whose SQL is built from a non-constant string
   (for example SpotBugs with the find-sec-bugs SQL-injection detectors; ArchUnit cannot see
   argument values, so it is not the tool for this rule), proven by a planted violation.
4. Test: two transactions on one pooled connection, bound to tenants A then B, never see each
   other's rows.

Residual risk accepted: an SQL-injection flaw could switch the tenant inside one transaction.
That flaw would already be a full compromise of the application role; control 3 is the
defence. Earlier work routed this decision to Dipesh (m-a474d8): **he may reverse it to
option B**.

## Decision 7 - Target credentials: envelope encryption, key outside the database

| Option | For | Against | Verdict |
|---|---|---|---|
| OpenBao key-value store (the deleted design's choice) | Secrets entirely outside the database; audit and leases | A new service on every delivery's path; another system to run highly available | Upgrade path |
| OpenBao transit engine (key in OpenBao, ciphertext in the database) | Key never leaves OpenBao | Same new service on the hot path | Upgrade path |
| **Envelope encryption in the JVM, key-encryption key from the deployment's secret store** | Standard library only (JDK AES-GCM); nothing new to run | Key custody and audit are the deployment's job | **Chosen for Release 1** |
| `pgcrypto` with the key passed in SQL | Simple | The key travels into the database session and can reach logs and statistics - not "outside the database" | Rejected |

Rules (written against the team's AEAD checklist; each MUST has a conformance test):

1. **Keys.** Each credential value gets its own random 256-bit data key; the value is encrypted
   with AES-256-GCM. The data key is wrapped with the key-encryption key (AES-256-GCM). The
   key-encryption key comes only from the deployment's secret store, read through environment
   variable names `PLATFORM_CREDENTIAL_KEK_<ID>` (32 bytes, base64) with the active id in
   `PLATFORM_CREDENTIAL_KEK_ACTIVE`; it is never in a file, a table or a log. In development it
   lives in the operating system's secret store.
2. **Nonces.** A random 96-bit nonce per encryption. A data key encrypts exactly one value (an
   update creates a new data key), so a key's GCM invocations are bounded by count, not by time.
   A key-encryption key is rotated before it has wrapped 2^32 data keys (in practice far
   earlier, at each rotation).
3. **Associated data binds the ciphertext to its row.** Value: `tc:v1|<tenant>|<target
   endpoint>|<field>`. Wrapped key: `dek:v1|<tenant>|<credential id>|<kek id>`. Test: a ciphertext
   copied to another tenant's or another endpoint's row fails to decrypt.
4. **Per-direction keys: not applicable** - this is encryption at rest in one direction; no
   value is ever encrypted by a second party.
5. **Error paths.** Encryption happens only on credential create and rotate; a rolled-back write
   wastes one random nonce, which is harmless. A failed decryption (tag mismatch, unknown key id)
   is a configuration error: the delivery is not retried, it is dead-lettered with the reason
   "target credential unreadable", an alert fires, and neither ciphertext nor plaintext is
   logged.
6. **Decryption only at send time,** in `platform-routing`, held in memory for the call only.
   No cache of decrypted values (a cache would silently remove the control's "only at send time"
   property).
7. **Rotation of the key-encryption key** re-wraps data keys only; the old key is retired when
   0 rows reference its id (a count query is the gate).
8. **Write-only API:** after creation a secret is never returned; it is masked in logs and
   screens (test in the logging phase).

## Consequences

- Keycloak is on the ingest path: a source cannot obtain a token while it is down (already-issued
  tokens keep working until expiry). Production needs Keycloak run highly available for the
  ingest availability figure (99.9%, verified at deployment).
- User administration spans Keycloak (identity) and the platform (tenant and roles); the
  console's tenant-admin screens do both through one flow.
- Release 1 depends on the deployment protecting one secret (the key-encryption key).

**What we give up:** tenant-aware tokens that other services could trust on their own (only
the platform resolves tenants); a central secrets service with leases and audit (until the
OpenBao upgrade path is taken); protection against an SQL-injection flaw switching the tenant
inside a transaction (option B).

## Revisit if

- A second service needs to authorise requests by tenant without calling the platform.
- Production hosting gains a secrets service or HSM (move the key-encryption key there through
  the one provider interface).
- A security review finds an injectable SQL path (move to option B).
- More than 200 tenants, or a tenant demands its own identity provider (federation per tenant).
