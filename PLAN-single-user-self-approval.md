# Plan: Single-User Self-Approval (Option B)

**Goal:** Allow a single TENANT_ADMIN user to create, submit, approve and publish
flow versions and contract versions without a second user.  
**Scope:** Remove the "author cannot approve their own work" constraint at
the database level, at the approval-function level, and in the Java services
that interpret the refusal. Leave the role enum and role-policy matrix
unchanged — TENANT_ADMIN already holds every needed permission.  
**Risk:** This weakens a security control (separation of duties). The change
is additive (new migration); the existing migrations are never touched.  
**Backward-compatible:** Yes — an existing two-user workflow still works; the
constraint is simply no longer enforced.

---

## Architecture overview

The self-approval guard lives in **three layers**, each of which must be
modified:

| Layer | What it does | Files |
|-------|-------------|-------|
| **DB CHECK constraint** | `approver_not_author` on two tables rejects any UPDATE/INSERT where `approver_id = author_id` | `interface_version` (V2_2_4), `contract_version` (V2_2_2) |
| **DB SECURITY DEFINER functions** | `config_approve_version()` and `config_approve_contract_version()` check `author_id = p_approver_id` and return `REFUSED_AUTHOR_IS_APPROVER` before even reaching the UPDATE | V2_2_4 (interface), V2_2_5_1 (contract) |
| **DB SECURITY DEFINER function** | `config_decide_db_table_approval()` checks `requested_by = actor` and returns `REFUSED_SELF_APPROVAL` | V2_4_1330 |
| **Java service** | `FlowAdminService.approveVersion()` maps `REFUSED_AUTHOR_IS_APPROVER` → 403 | `FlowAdminService.java:230` |
| **Java service** | `ContractAdminService.approveContractVersion()` maps `REFUSED_AUTHOR_IS_APPROVER` → 403 | `ContractAdminService.java:167` |
| **Java service** | `ApprovedTableService` maps `REFUSED_SELF_APPROVAL` → 403 | `ApprovedTableService.java:257` |

---

## Step-by-step implementation

### Step 1: New Flyway migration

**Create file:**
`platform-config/src/main/resources/db/migration/V2_4_1420__allow_self_approval.sql`

This is the ONLY database change. It:
1. Drops the `approver_not_author` CHECK constraint on both tables
2. Replaces the two approval functions to remove the author check
3. Replaces the DB-table approval function to remove the self-approval check

```sql
-- V2_4_1420: Allow self-approval (single-user workflow).
-- Drops the approver_not_author CHECK on interface_version and contract_version,
-- and replaces the three approval functions to remove the author/self check.
-- Backward-compatible: a two-user workflow still works; the constraint is simply
-- no longer enforced.

-- 1. Drop the CHECK constraints
ALTER TABLE public.interface_version DROP CONSTRAINT approver_not_author;
ALTER TABLE public.contract_version DROP CONSTRAINT approver_not_author;

-- 2. Replace config_approve_version (interface/flow versions)
--    Original: V2_2_4__config_interface_versions.sql lines 124-148
--    Change: remove the author_id = p_approver_id branch (lines 135-138)
CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v public.interface_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.interface_version
        WHERE id = p_version_id
          AND tenant_id = current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', p_approver_id);
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.interface_version
        SET status = 'APPROVED', approver_id = p_approver_id, decided_at = now()
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

-- 3. Replace config_approve_contract_version (contract versions)
--    Original: V2_2_5_1__contract_target_roles_and_approval.sql lines 25-45
--    Change: remove the author_id = p_approver_id branch (lines 38-39)
CREATE OR REPLACE FUNCTION public.config_approve_contract_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v public.contract_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.contract_version
        WHERE id = p_version_id
          AND tenant_id = current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.contract_version
        SET status = 'APPROVED', approver_id = p_approver_id
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

-- 4. Replace config_decide_db_table_approval (DB target table approval)
--    Original: V2_4_1330__db_target_approved_table.sql lines 114-155
--    Change: remove the requested_by = actor branch (lines 137-138)
CREATE OR REPLACE FUNCTION public.config_decide_db_table_approval(p_id uuid, p_decision text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    r       public.db_target_approved_table%ROWTYPE;
    actor   text := nullif(pg_catalog.current_setting('app.actor_id', true), '');
    current_hash text;
BEGIN
    IF p_decision IS NULL OR p_decision NOT IN ('APPROVE', 'REVOKE') THEN
        RETURN 'REFUSED_UNKNOWN_DECISION';
    END IF;
    IF actor IS NULL THEN
        RETURN 'REFUSED_NO_ACTOR';
    END IF;
    SELECT * INTO r FROM public.db_target_approved_table
        WHERE id = p_id
          AND tenant_id = pg_catalog.current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF p_decision = 'APPROVE' THEN
        IF r.status <> 'PENDING_APPROVAL' THEN
            RETURN 'REFUSED_' || r.status;
        END IF;
        -- Self-approval check REMOVED (single-user workflow)
        SELECT public.db_target_fingerprint(s.settings) INTO current_hash FROM public.system s
            WHERE s.id = r.system_id AND s.tenant_id = r.tenant_id;
        IF current_hash IS DISTINCT FROM r.target_hash THEN
            RETURN 'REFUSED_TARGET_CHANGED';
        END IF;
        UPDATE public.db_target_approved_table
            SET status = 'APPROVED', decided_by = actor, decided_at = pg_catalog.now()
            WHERE id = p_id;
        RETURN 'APPROVED';
    END IF;
    -- REVOKE path (unchanged)
    IF r.status <> 'APPROVED' THEN
        RETURN 'REFUSED_' || r.status;
    END IF;
    UPDATE public.db_target_approved_table
        SET status = 'REVOKED', revoked_by = actor, revoked_at = pg_catalog.now()
        WHERE id = p_id;
    RETURN 'REVOKED';
END $$;
```

**Important:** Copy the REVOKE path of `config_decide_db_table_approval` from
the original migration V2_4_1330 (lines 150-157) to complete the function.
The snippet above shows only the first part — the full function must include
both APPROVE and REVOKE branches.

---

### Step 2: Java service changes (2 files)

The DB functions will no longer return `REFUSED_AUTHOR_IS_APPROVER` or
`REFUSED_SELF_APPROVAL`, so the Java `case` branches that handle them become
dead code. They should be removed to avoid confusion, but leaving them is
also safe (they just never execute).

#### File 1: `platform-config/src/main/java/io/integrationplatform/config/flow/application/FlowAdminService.java`

**Find** (around line 230):
```java
case "REFUSED_AUTHOR_IS_APPROVER" -> new ApprovalOutcome(403,
        ConfigApiException.refusal(403, "AUTHOR_IS_APPROVER").body());
```

**Action:** Remove these two lines entirely. The `default` branch already
handles any unexpected refusal as a 409.

#### File 2: `platform-config/src/main/java/io/integrationplatform/config/contract/application/ContractAdminService.java`

**Find** (around line 167):
```java
case "REFUSED_AUTHOR_IS_APPROVER" -> throw ConfigApiException.refusal(403, "AUTHOR_IS_APPROVER");
```

**Action:** Remove this line entirely. The `default` branch handles it.

#### File 3: `platform-config/src/main/java/io/integrationplatform/config/system/application/ApprovedTableService.java`

**Find** (around line 257):
```java
case "REFUSED_SELF_APPROVAL" -> throw ConfigApiException.refusal(403, "REQUESTER_IS_APPROVER");
```

**Action:** Remove this line entirely.

---

### Step 3: Rebuild

After creating the migration file and editing the Java files:

```powershell
# From D:\Galaxy\integration-platform
.\mvnw.cmd install -DskipTests -q
# Then restart Spring Boot (the migration runs automatically on startup)
```

---

### Step 4: Test updates

The following tests verify the self-approval refusal. After this change they
will FAIL because the DB no longer refuses self-approval. Each test must be
updated or removed.

#### 4a. DB proof test (SQL)

**File:** `platform-config/src/test/resources/db/proof/22_scenario_approvals.sql`

Lines 7-24 test that `config_approve_version` returns
`REFUSED_AUTHOR_IS_APPROVER` when the author tries to approve. After this
change it returns `APPROVED`.

**Action:** Change the expected outcome from `REFUSED_AUTHOR_IS_APPROVER` to
`APPROVED` (line 14). Remove or update the refusal-record check on lines
26-44 (no refusal row is inserted any more).

#### 4b. Container tests (Java, run with Testcontainers — need Docker)

| Test file | What it tests | Action |
|-----------|--------------|--------|
| `platform-app/src/test/java/.../roles/RoleMatrixContainerTest.java:223-232` | "Refused: an approver approves their OWN version — 403 AUTHOR_IS_APPROVER" | Change assertion: expect 200 APPROVED, not 403 |
| `platform-app/src/test/java/.../roles/RoleMatrixContainerTest.java:274` | Same for contract version | Change assertion: expect 200 APPROVED, not 403 |
| `platform-app/src/test/java/.../e5/contract/E5ContractVersionsContainerTest.java:194-211` | "@negative @security Scenario: The author cannot approve their own contract version" | Remove or invert: expect APPROVED |
| `platform-app/src/test/java/.../e5/contract/E12ConfigAuditTrailContainerTest.java:138-157` | "the author's refused approval lands none" | Update: the audit now records an APPROVED event |
| `platform-config/src/test/java/.../e3/T019ConfigLifecycleContainerTest.java:48-61` | "Scenario: The author cannot approve" | Remove or invert |
| `platform-config/src/test/java/.../e3/LaneCLifecycle.java` | Lifecycle helper that uses two users | Make the approver the same user as the author |
| `platform-config/src/test/java/.../system/application/ApprovedTableServiceTest.java:305` | "self-approval never dials the partner" | Update: self-approval now succeeds |
| `platform-config/src/test/java/.../system/application/ApprovedTableRefusalAuditTest.java:204` | "Not audited here: a self-approval" | Update: no refusal any more |

#### 4c. Completeness test (checks every endpoint has a rule)

**File:** `platform-app/src/test/java/.../roles/ApiOperationRulesCompletenessTest.java`

**Action:** No change needed — this test checks that every endpoint has a role
rule, not the self-approval semantics.

---

### Step 5: Seed data simplification (optional)

If you want only one user in the seed:

**File:** `deploy/hostinger/seed/seed.sql`

Change the `seed_user` temp table to only insert the TENANT_ADMIN row:
```sql
INSERT INTO seed_user VALUES
  ('showcase-a', :'sub_admin_a', 'TENANT_ADMIN');
```

Remove the configurator and approver psql variables from the `psql -v` call.
The SUPPORT and READ_ONLY users we created via Keycloak in the previous
session can be deleted from Keycloak admin (localhost:8180) if desired —
they have no effect on the system.

---

## What does NOT change

| Component | Reason |
|-----------|--------|
| `Role.java` enum (5 values) | Still needed for the DB schema's CHECK constraint on `role_grant` |
| `RolePolicy.java` matrix | TENANT_ADMIN is already in every operation's allowed set |
| `ApiOperationRules` implementations (13 files) | The endpoint → operation mapping is unchanged |
| `RoleEnforcementInterceptor` | Still checks the user holds an allowed role — TENANT_ADMIN passes |
| `ipaas_login_bootstrap()` function | Still resolves token → user + tenant |
| `DestinationGuard` / allowed-hosts | Unrelated to roles |
| `config_version_refusal` table | Still exists; `NOT_PENDING_APPROVAL` refusals still write to it |

---

## Files changed — summary for commit

### New files (1)
1. `platform-config/src/main/resources/db/migration/V2_4_1420__allow_self_approval.sql`

### Modified files (3 Java, 1+ test)
1. `platform-config/src/main/java/.../flow/application/FlowAdminService.java` — remove `REFUSED_AUTHOR_IS_APPROVER` case
2. `platform-config/src/main/java/.../contract/application/ContractAdminService.java` — remove `REFUSED_AUTHOR_IS_APPROVER` case
3. `platform-config/src/main/java/.../system/application/ApprovedTableService.java` — remove `REFUSED_SELF_APPROVAL` case
4. Test files listed in Step 4 (8 files)

### Total estimate
- **Migration SQL:** ~90 lines (three function replacements + two ALTER TABLE)
- **Java edits:** 3 lines removed across 3 files
- **Test updates:** 8 test files, each a small assertion change or test removal
- **Effort:** ~1-2 hours for an experienced developer; no new classes, no new endpoints

---

## Verification checklist

After implementing:

1. [ ] Start Spring Boot — Flyway should log: `Migrating schema "public" to version "2.4.1420 - allow self approval"`
2. [ ] Log in as `admin` (TENANT_ADMIN)
3. [ ] Create a System → works (already worked before)
4. [ ] Create a Contract → submit for approval → approve as the SAME user → should succeed (was 403 before)
5. [ ] Create a Flow → submit for approval → approve as the SAME user → should succeed (was 403 before)
6. [ ] Publish the approved flow → should succeed
7. [ ] If DB targets are configured: request a table approval → approve as the SAME user → should succeed
8. [ ] `mvn test` passes (after Step 4 test updates)

---

## Rollback

If this needs to be reversed:

```sql
-- V2_4_1430__restore_self_approval_guard.sql
ALTER TABLE public.interface_version
    ADD CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id);
ALTER TABLE public.contract_version
    ADD CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id);
-- Then restore the three functions from the original migrations (V2_2_4, V2_2_5_1, V2_4_1330)
```

**Prerequisite:** No row in either table has `approver_id = author_id`, or the
ALTER TABLE will fail. If self-approvals already exist, they must be updated
first (set `approver_id` to a different user).
