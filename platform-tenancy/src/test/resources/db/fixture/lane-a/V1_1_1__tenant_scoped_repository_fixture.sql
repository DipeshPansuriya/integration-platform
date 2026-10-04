-- Test-only fixture (lane A, T-004): a tenant-scoped table with rows of two tenants, loaded only by the
-- TenantScopedRepository container test (spring.flyway.locations adds classpath:db/fixture/lane-a). Never shipped:
-- it lives in src/test/resources. No row-level security here on purpose - this test proves the application filter
-- (the first wall) alone; lane C's RLS (T-005, T-157) is the second wall.
CREATE TABLE lane_a_fixture_note (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    title     text NOT NULL
);

INSERT INTO lane_a_fixture_note (id, tenant_id, title) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'a-first'),
    ('a0000000-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-00000000000a', 'a-second'),
    ('b0000000-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-00000000000b', 'b-only');
