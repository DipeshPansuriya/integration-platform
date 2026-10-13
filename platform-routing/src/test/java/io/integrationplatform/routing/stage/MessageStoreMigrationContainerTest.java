package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The message and payload migrations against a real PostgreSQL (T-111, ADR-014 Decision 1): the day-partitioned
 * parents and their pre-created days, the error-deny row-level security, exactly the ADR-014 grants
 * (conformance test 3: UPDATE/DELETE on payload and DELETE on message as {@code ipaas_app} fail with 42501), and
 * the work-tenant definer function the stage consumers resolve their tenant through (ADR-011 Decision 8).
 */
@Tag("container")
class MessageStoreMigrationContainerTest {

    private final StageFixture db = StageFixture.get();

    @Test
    void theMessageAndPayloadTablesAreDayPartitionedParentsOfTodayPartitions() throws SQLException {
        UUID messageId = db.seedMessage(StageFixture.TENANT_A);

        try (Connection owner = db.owner();
                PreparedStatement insertPayload = owner.prepareStatement(
                        "insert into public.payload (message_id, tenant_id, accepted_at, body)"
                                + " values (?, ?, now(), ?::jsonb)");
                PreparedStatement landsIn = owner.prepareStatement(
                        "select tableoid::regclass::text from public.message where id = ?");
                PreparedStatement payloadLandsIn = owner.prepareStatement(
                        "select tableoid::regclass::text from public.payload where message_id = ?")) {
            insertPayload.setObject(1, messageId);
            insertPayload.setObject(2, StageFixture.TENANT_A);
            insertPayload.setString(3, "{\"amount\":1}");
            insertPayload.executeUpdate();
            landsIn.setObject(1, messageId);
            try (ResultSet row = landsIn.executeQuery()) {
                row.next();
                assertThat(row.getString(1)).as("the message lands in its accepted day's child partition").startsWith(
                        "message_");
            }
            payloadLandsIn.setObject(1, messageId);
            try (ResultSet row = payloadLandsIn.executeQuery()) {
                row.next();
                assertThat(row.getString(1)).as("the payload sits in its message's day").startsWith("payload_");
            }
        }

        try (Connection owner = db.owner(); Statement sql = owner.createStatement();
                ResultSet days = sql.executeQuery(
                        "select count(*) from pg_class where relname ~ '^(message|payload)_20[0-9]{6}$'")) {
            days.next();
            // CURRENT_DATE - 1 .. + 7 pre-created, message and payload: at least 16 child partitions.
            assertThat(days.getInt(1)).as("both families have their days pre-created").isGreaterThanOrEqualTo(16);
        }
    }

    @Test
    void rlsDeniesUnstampedAccessAndShowsOnlyTheBoundTenant() throws SQLException {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);

        try (Connection app = db.app(); Statement sql = app.createStatement()) {
            // Error-deny (ADR-009 Decision 5): no stamp fails the statement, it never returns foreign rows.
            assertThatThrownBy(() -> sql.executeQuery("select count(*) from public.message"))
                    .as("no stamp: the statement fails, it never returns rows")
                    .isInstanceOf(SQLException.class);
        }
        try (Connection app = db.app()) {
            // The stamp is per transaction (set_config is_local) - query inside the stamped transaction.
            app.setAutoCommit(false);
            try (Statement sql = app.createStatement();
                    PreparedStatement stamp = app.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, StageFixture.TENANT_A.toString());
                stamp.execute();
                try (ResultSet seen = sql.executeQuery(
                        "select count(*) from public.message where id = '" + rowOfTenantA + "'")) {
                    seen.next();
                    assertThat(seen.getInt(1)).as("tenant A sees its own row under RLS").isEqualTo(1);
                }
                try (PreparedStatement stampedQuery = app.prepareStatement(
                        "select count(*) from public.message where tenant_id <> ?::uuid")) {
                    stampedQuery.setString(1, StageFixture.TENANT_A.toString());
                    try (ResultSet other = stampedQuery.executeQuery()) {
                        other.next();
                        assertThat(other.getInt(1)).as("tenant A sees no row of another tenant").isEqualTo(0);
                    }
                }
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    void theApplicationRoleHasExactlyTheAdrGrants() throws SQLException {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);

        List<String> tablePrivileges = new ArrayList<>();
        List<String> messageUpdatabaleColumns = new ArrayList<>();
        try (Connection owner = db.owner(); Statement sql = owner.createStatement()) {
            try (ResultSet grants = sql.executeQuery(
                    "select table_name, privilege_type from information_schema.table_privileges"
                            + " where grantee = 'ipaas_app' and table_schema = 'public'"
                            + " and table_name in ('message', 'payload') order by table_name, privilege_type")) {
                while (grants.next()) {
                    tablePrivileges.add(grants.getString(1) + ":" + grants.getString(2));
                }
            }
            try (ResultSet columns = sql.executeQuery(
                    "select column_name from information_schema.column_privileges"
                            + " where grantee = 'ipaas_app' and table_schema = 'public' and table_name = 'message'"
                            + " and privilege_type = 'UPDATE' order by column_name")) {
                while (columns.next()) {
                    messageUpdatabaleColumns.add(columns.getString(1));
                }
            }
        }
        assertThat(tablePrivileges)
                .as("ADR-014 grants: message SELECT/INSERT at table level, payload SELECT/INSERT only"
                        + " (UPDATE is a column privilege)")
                .containsExactly("message:INSERT", "message:SELECT", "payload:INSERT", "payload:SELECT");
        assertThat(messageUpdatabaleColumns)
                .as("only the state columns of message are updatable by the application")
                .containsExactly("routed_at", "state", "updated_at");

        // A failed statement aborts its transaction (25P02) - every probe runs in its own stamped transaction.
        assertThat(stampedSqlState("update public.payload set body = body"))
                .as("ADR-014 conformance test 3: UPDATE on payload fails with 42501").isEqualTo("42501");
        assertThat(stampedSqlState("delete from public.payload"))
                .as("DELETE on payload fails with 42501").isEqualTo("42501");
        assertThat(stampedSqlState("delete from public.message"))
                .as("DELETE on message fails with 42501").isEqualTo("42501");
        assertThat(stampedSqlState("update public.message set state = 'ROUTE_FAILED', routed_at = now(),"
                + " updated_at = now() where id = '" + rowOfTenantA + "'"))
                .as("the stage-1 state change is exactly what the application role may do").isNull();
    }

    /**
     * Runs one statement as the stamped {@code ipaas_app} in its own transaction; returns its SQLSTATE, or
     * {@code null} when the statement went through.
     */
    private String stampedSqlState(String statement) throws SQLException {
        try (Connection app = db.app()) {
            app.setAutoCommit(false);
            try (Statement sql = app.createStatement();
                    PreparedStatement stamp = app.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, StageFixture.TENANT_A.toString());
                stamp.execute();
                try {
                    sql.executeUpdate(statement);
                    app.rollback();
                    return null;
                } catch (SQLException failed) {
                    app.rollback();
                    return failed.getSQLState();
                }
            }
        }
    }

    @Test
    void theWorkTenantFunctionResolvesTheRowTenantAndNothingElse() throws SQLException {
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);
        UUID deliveryOfTenantB = seedDelivery(StageFixture.TENANT_B);

        try (Connection app = db.app();
                PreparedStatement lookup = app.prepareStatement("select public.ipaas_work_tenant(?, ?)")) {
            assertThat(lookupReturns(lookup, "MESSAGE", rowOfTenantA))
                    .as("the MESSAGE kind resolves from the message row").contains(StageFixture.TENANT_A);
            assertThat(lookupReturns(lookup, "DELIVERY", deliveryOfTenantB))
                    .as("the DELIVERY kind resolves from the delivery row").contains(StageFixture.TENANT_B);
            assertThat(lookupReturns(lookup, "MESSAGE", UUID.randomUUID()))
                    .as("an id naming no row returns null - the stage base does nothing").isEmpty();
        }
    }

    @Test
    void theOwnerRunMaintenanceSeesTheTableFamilyWithoutAStamp() throws SQLException {
        // Not FORCE (ADR-014): the ADR-011 purge guard and the maintenance identity read the rows as the owner.
        UUID rowOfTenantA = db.seedMessage(StageFixture.TENANT_A);
        try (Connection owner = db.owner(); Statement sql = owner.createStatement();
                ResultSet seen = sql.executeQuery(
                        "select count(*) from public.message where id = '" + rowOfTenantA + "'")) {
            seen.next();
            assertThat(seen.getInt(1)).as("the owner is row-level security exempt on the unforced table")
                    .isEqualTo(1);
        }
    }

    private UUID seedDelivery(UUID tenantId) throws SQLException {
        UUID id = UUID.fromString("43434343-0000-4000-8000-000000000004");
        try (Connection owner = db.owner(); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.delivery (id, tenant_id, state) values ('" + id + "', '" + tenantId
                    + "', 'PENDING')");
        }
        return id;
    }

    private java.util.Optional<UUID> lookupReturns(PreparedStatement lookup, String kind, UUID id)
            throws SQLException {
        lookup.setString(1, kind);
        lookup.setObject(2, id);
        try (ResultSet tenant = lookup.executeQuery()) {
            tenant.next();
            return java.util.Optional.ofNullable(tenant.getObject(1, UUID.class));
        }
    }
}