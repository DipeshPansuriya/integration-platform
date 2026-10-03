---
type: ADR
title: "ADR-009 - Tenant directory under row-level security, with one trusted login bootstrap function"
status: "Accepted (Dipesh approved 2026-09-30; RECREATED 2026-10-02 from surviving sources) - Dipesh re-confirms the recreation"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0) - recreation"
project: ipass/integration-platform
provenance: recreation
related:
  - ADR-002-datastore.md
  - ADR-007-identity.md
  - ADR-011-targets-contracts-bulk-ordering.md
---

# ADR-009 - Tenant directory under row-level security, with one trusted login bootstrap function

> **Recreation.** The original file (`documents/adr/ADR-009-tenant-directory-rls.md`) was deleted
> with the old repository on 2026-10-02. It is rebuilt from the migration draft that implemented
> it and the memory entries that recorded its approval and its security judgement
> (`ws/_specs/project-plan.md:169`, provenance table). The draft survives in full, so this is the
> most complete recreation in the set. The **Update 2026-10-02** block at the end is new.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id is the stable key.

## Context

*Source: `ws/_specs/f2-tenant-directory-rls-migration-draft.md:4`, `:27-29`, `:45-52`;
`ws/_memory/shared.md:113` (m-6c7ae2).*

Every tenant-owned table is protected by a row-level security policy keyed on the session
setting `app.tenant_id`. The tenant directory table itself (`tenant`) was granted `SELECT` to the
application role with no policy - a deliberate gap from the first foundation phase ("tenant-dir
GRANT unpoliced"). It could not simply be policed, because login has a chicken-and-egg problem:
before login the tenant is not known, so the setting is empty, yet the login resolver must look
up which tenant the authenticated subject belongs to.

## Decision

*Source: `ws/_specs/f2-tenant-directory-rls-migration-draft.md:31-79` (migration shape), `:81-97`
(choices the implementing lane must not soften); approval: `ws/_memory/shared.md:111`
(m-a474d8, "ADR-009 tenant-directory RLS APPROVED by Dipesh 2026-09-30").*

1. **Police the directory down to the own row.** Enable row-level security on `tenant`; policy
   `tenant_dir_self USING (id = current_setting('app.tenant_id')::uuid)`.
2. **Deny writes by grant, not by policy absence.** `REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON
   tenant FROM ipaas_app`, so a future "GRANT ALL" convenience cannot reopen the write path.
   Hence no `WITH CHECK` clause - there is no write surface to check.
3. **One trusted path across the pre-tenant boundary:** a single `SECURITY DEFINER`, `STABLE`
   SQL function `ipaas_login_bootstrap(p_external_id text)`, owned by the migration owner,
   returning only the narrow projection the resolver needs (user id and status, tenant id, code,
   status, time zone) for `ACTIVE` users of `ACTIVE` tenants. `SET search_path = pg_catalog,
   public` and schema-qualified names inside (the standard definer hijack guard). `REVOKE ALL
   ... FROM PUBLIC`; `GRANT EXECUTE` to the application role only.
4. **The resolver binds the tenant from that function**, then reads role grants and its own
   directory row through ordinary tenant-policed repositories - never a second definer call,
   never a raw cross-tenant select (`ws/_specs/f2-tenant-directory-rls-migration-draft.md:99-118`).
5. **Fail-closed on an empty setting, error-deny form**: the cast of an empty setting fails the
   statement. Judged to fail closed everywhere by karan-security
   (`ws/_memory/shared.md:100`, m-21b00f).

## Gate (counts, not ratios)

*Source: `ws/_specs/f2-tenant-directory-rls-migration-draft.md:120-138`;
`ws/_memory/shared.md:112` (m-c45702).*

| # | Case | Pass shape |
|---|---|---|
| d1 | Setting = tenant A, select tenant B's directory row | 0 rows |
| d2 | Empty setting, directory select | 0 rows or a statement error - the harness states which |
| d3 | As the application role: INSERT, UPDATE, DELETE, TRUNCATE on `tenant` | 4 permission-denied results (SQLSTATE 42501) |
| d4 | Bootstrap with own subject / a foreign subject | 1 row / 0 rows |

Red first (migration absent): the 4 new cases fail. Green: all pass. The forged-tenant denial
suite is unchanged and passes.

## Consequences

*Source: `ws/_specs/f2-tenant-directory-rls-migration-draft.md:140-161`.*

- Rollback before apply is one `git revert`; after apply, a revert migration drops the
  function and policy and restores the plain grant (the login path falls back in the same
  commit).
- Platform-operator users (no tenant) match no setting value; operator login was deferred to an
  operations phase by Dipesh on 2026-10-01 (`ws/_memory/shared.md:94`, m-e9fbe4) and gets its own security
  design and attack suite then.

**What we give up:** one deliberate hole in the "everything is policed" rule - a single definer
function that crosses the tenant boundary. It is kept narrow, keyed by subject and owned by the
migration owner.

## Revisit if

*Source: `ws/_memory/shared.md:145-146` (m-c45702, m-6c7ae2: ONE trusted pre-tenant path);
`ws/_specs/f2-tenant-directory-rls-migration-draft.md:158-159` and `ws/_memory/shared.md:144`
(m-a474d8: operator no-tenant login scope left open).*

- A second pre-tenant lookup is needed (it must then be its own reviewed definer function - see
  ADR-011's background-work resolver, which follows this pattern).
- Operator (no-tenant) login is designed.

## Update 2026-10-02 (rebuild, new - not part of the original record)

*Added in the recreation - new team decision, for Dipesh's re-confirmation.*

- The migration version numbers of the deleted build (V2, V3) are not carried over; the rebuild
  assigns its own Flyway numbers. The shape above is what is binding.
- The bootstrap is keyed by the IdP subject; ADR-007 makes the key the pair (issuer, subject)
  and adds the equivalent bootstrap for source-system machine credentials.
- ADR-007 changes how the session setting is stamped (transaction-scoped instead of at
  connection checkout). The policy expression and the error-deny form here are unchanged.
- ADR-011 adds the same one-trusted-function pattern for background work (Kafka consumers,
  scheduled jobs) that must find a row's tenant before the setting is bound.
