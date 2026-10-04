-- T-002 (lane D, E1): TEST-ONLY fixture for the forged-tenant denial suite. Never on the main migration path:
-- the suite applies it as the Flyway owner with its own history table (flyway_fixture_lane_d_history) after the
-- application's migrations ran. It needs lane C's tenant table and ipaas_app role (ADR-009).
--
-- A tenant-owned table in exactly the form every tenant-owned table takes: row-level security in the error-deny
-- form (an empty app.tenant_id fails the cast, ADR-009 Decision 5) and SELECT only for the application role.
CREATE TABLE probe_row (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    label     text NOT NULL
);

ALTER TABLE probe_row ENABLE ROW LEVEL SECURITY;

CREATE POLICY probe_row_tenant ON probe_row
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

REVOKE ALL ON probe_row FROM PUBLIC;
GRANT SELECT ON probe_row TO ipaas_app;
