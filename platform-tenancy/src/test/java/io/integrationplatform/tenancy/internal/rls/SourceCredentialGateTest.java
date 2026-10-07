package io.integrationplatform.tenancy.internal.rls;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.OTHER_ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A_PENDING;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A_REVOKED;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_B;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_C;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CRED_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CRED_B;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CUSTOMER_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_B;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-098 on the raw-SQL path (E4-F1-S2; ADR-007 Decisions 2-3; ADR-009 pattern): {@code public.source_credential} under
 * row-level security in the error-deny form with narrow grants, and the second trusted pre-tenant function
 * {@code ipaas_client_bootstrap(issuer, client id)}, which returns the narrow projection for ACTIVE credentials of
 * ACTIVE tenants only. Every statement runs as the restricted application login unless it says otherwise.
 */
@Tag("container")
class SourceCredentialGateTest {

    private static final String PERMISSION_DENIED = "42501";
    private static final String UNDEFINED_PARAMETER = "42704";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String BOOTSTRAP = "select credential_id, credential_status, source_system_id, customer_id,"
            + " tenant_id, tenant_code, tenant_status, time_zone from public.ipaas_client_bootstrap(?, ?)";

    private RlsTestDatabase db;

    @BeforeEach
    void database() throws SQLException {
        db = RlsTestDatabase.get();
        SourceCredentialFixtures.seed();
    }

    // ---- the definer function ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S2: the client bootstrap resolves an ACTIVE credential to its source system, customer and tenant")
    void theBootstrapResolvesAnActiveCredential() throws SQLException {
        try (Connection app = db.appConnection()) {
            assertThat(bootstrap(app, ISSUER, CLIENT_A)).containsExactly(String.join("|",
                    CRED_A.toString(), "ACTIVE", SYSTEM_A.toString(), CUSTOMER_A, TENANT_A.toString(), "tenant-a",
                    "ACTIVE", "Asia/Kolkata"));
        }
    }

    @Test
    @DisplayName("S2 @negative @security: PENDING, REVOKED, suspended-tenant, unknown and foreign-issuer clients resolve to nothing")
    void theBootstrapReturnsNothingForAnInactiveOrUnknownCredential() throws SQLException {
        try (Connection app = db.appConnection()) {
            assertThat(bootstrap(app, ISSUER, CLIENT_A_PENDING)).as("PENDING").isEmpty();
            assertThat(bootstrap(app, ISSUER, CLIENT_A_REVOKED)).as("REVOKED").isEmpty();
            assertThat(bootstrap(app, ISSUER, CLIENT_C)).as("tenant SUSPENDED").isEmpty();
            assertThat(bootstrap(app, ISSUER, "no-such-client")).as("unknown").isEmpty();
            assertThat(bootstrap(app, OTHER_ISSUER, CLIENT_A)).as("foreign issuer").isEmpty();
            assertThat(bootstrap(app, ISSUER, CLIENT_B)).as("tenant B's active credential still resolves").hasSize(1);
        }
    }

    @Test
    @DisplayName("S2 @security: the bootstrap runs before any tenant is bound and is executable by the application role only")
    void theBootstrapIsTheOnlyPreTenantPathAndOnlyForTheApplicationRole() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> count(app, "select count(*) from public.source_credential"))
                    .as("without the bootstrap, an unbound read fails closed")
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNDEFINED_PARAMETER));
            app.rollback();
            assertThat(bootstrap(app, ISSUER, CLIENT_A)).hasSize(1);
            app.rollback();
        }
        try (Connection outsider = db.outsiderConnection()) {
            assertThatThrownBy(() -> bootstrap(outsider, ISSUER, CLIENT_A))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
        }
        try (Connection owner = db.ownerConnection(); Statement sql = owner.createStatement();
                ResultSet definer = sql.executeQuery("select p.prosecdef, p.provolatile, p.proconfig::text"
                        + " from pg_proc p where p.proname = 'ipaas_client_bootstrap'")) {
            assertThat(definer.next()).isTrue();
            assertThat(definer.getBoolean(1)).as("SECURITY DEFINER").isTrue();
            assertThat(definer.getString(2)).as("STABLE").isEqualTo("s");
            assertThat(definer.getString(3)).as("search_path pinned").contains("search_path=pg_catalog, public");
        }
    }

    // ---- row-level security and grants -------------------------------------------------------------------------

    @Test
    @DisplayName("S2 @negative @tenant: ipaas_app sees only the bound tenant's credentials; another tenant's row is invisible")
    void theApplicationRoleSeesOnlyTheBoundTenantsCredentials() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            assertThat(uuids(app, "select distinct tenant_id from public.source_credential")).containsExactly(TENANT_A);
            assertThat(count(app, "select count(*) from public.source_credential where id = '" + CRED_B + "'"))
                    .as("tenant B's credential by id").isZero();
            assertThat(updated(app, "update public.source_credential set status = 'REVOKED', revoked_at = now()"
                    + " where id = '" + CRED_B + "'")).as("tenant B's credential cannot be revoked from A").isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("S2 @security: narrow grants - UPDATE only on status, revoked_at, idp_disable_pending; never DELETE")
    void grantsAreNarrow() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            for (String statement : List.of(
                    "update public.source_credential set client_id = 'stolen' where id = '" + CRED_A + "'",
                    "update public.source_credential set customer_id = 'CUSTOMER-B' where id = '" + CRED_A + "'",
                    "update public.source_credential set tenant_id = '" + TENANT_B + "' where id = '" + CRED_A + "'",
                    "update public.source_credential set source_system_id = '" + SYSTEM_B + "' where id = '"
                            + CRED_A + "'",
                    "delete from public.source_credential where id = '" + CRED_A + "'",
                    "truncate public.source_credential")) {
                stamp(app, TENANT_A);
                assertThatThrownBy(() -> updated(app, statement))
                        .as(statement)
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
                app.rollback();
            }
            stamp(app, TENANT_A);
            assertThat(updated(app, "update public.source_credential set idp_disable_pending = false"
                    + " where id = '" + CRED_A + "'")).isEqualTo(1);
            app.rollback();
        }
    }

    @Test
    @DisplayName("S2 @negative @tenant: a credential cannot point at another tenant's source system (composite key)")
    void aCredentialCannotPointAtAnotherTenantsSystem() throws SQLException {
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            assertThatThrownBy(() -> updated(app, "insert into public.source_credential (tenant_id, source_system_id,"
                    + " customer_id, issuer, client_id) values ('" + TENANT_A + "', '" + SYSTEM_B + "', 'X', '"
                    + ISSUER + "', 'cross-" + UUID.randomUUID() + "')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
            app.rollback();
            stamp(app, TENANT_A);
            assertThatThrownBy(() -> updated(app, "insert into public.source_credential (tenant_id, source_system_id,"
                    + " customer_id, issuer, client_id) values ('" + TENANT_B + "', '" + SYSTEM_B + "', 'X', '"
                    + ISSUER + "', 'forged-" + UUID.randomUUID() + "')"))
                    .as("a row of another tenant fails the policy's WITH CHECK")
                    .isInstanceOf(SQLException.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("S2: a new credential starts PENDING; client ids are unique per issuer; closed sets are CHECKed")
    void newCredentialsStartPendingAndTheShapeIsChecked() throws SQLException {
        String client = "new-" + UUID.randomUUID();
        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            stamp(app, TENANT_A);
            assertThat(updated(app, "insert into public.source_credential (tenant_id, source_system_id, customer_id,"
                    + " issuer, client_id) values ('" + TENANT_A + "', '" + SYSTEM_A + "', 'CUSTOMER-A', '" + ISSUER
                    + "', '" + client + "')")).isEqualTo(1);
            assertThat(text(app, "select status || '|' || idp_disable_pending || '|' || (revoked_at is null)"
                    + " || '|' || (created_at is not null) from public.source_credential where client_id = '" + client
                    + "'")).isEqualTo("PENDING|false|true|true");
            app.rollback();
            for (String bad : List.of(
                    "update public.source_credential set status = 'EXPIRED' where id = '" + CRED_A + "'",
                    "update public.source_credential set status = 'REVOKED' where id = '" + CRED_A + "'",
                    "update public.source_credential set idp_disable_pending = true where id = '" + CRED_A + "'")) {
                stamp(app, TENANT_A);
                assertThatThrownBy(() -> updated(app, bad))
                        .as(bad)
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
                app.rollback();
            }
            stamp(app, TENANT_A);
            assertThatThrownBy(() -> updated(app, "insert into public.source_credential (tenant_id, source_system_id,"
                    + " customer_id, issuer, client_id) values ('" + TENANT_A + "', '" + SYSTEM_A + "', 'X', '"
                    + ISSUER + "', '" + CLIENT_A + "')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
            app.rollback();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static List<String> bootstrap(Connection connection, String issuer, String clientId) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement(BOOTSTRAP)) {
            call.setString(1, issuer);
            call.setString(2, clientId);
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= 8; i++) {
                        columns.add(rs.getString(i));
                    }
                    rows.add(String.join("|", columns));
                }
            }
            return rows;
        }
    }

    private static void stamp(Connection connection, UUID tenant) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenant.toString());
            set.execute();
        }
    }

    private static List<UUID> uuids(Connection connection, String query) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    private static long count(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int updated(Connection connection, String statement) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            return sql.executeUpdate(statement);
        }
    }
}
