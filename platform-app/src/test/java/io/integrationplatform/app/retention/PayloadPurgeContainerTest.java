package io.integrationplatform.app.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.app.scheduler.GrantAppRoleFlywayCallback;
import io.integrationplatform.tenancy.internal.persistence.JdbcPartitionPurge;
import io.integrationplatform.tenancy.port.PartitionPurge;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.callback.Callback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 T-142 / T-189 (ADR-014 Decision 3; backlog "A clean day is dropped" / "A day with a dead letter survives"):
 * the payload purge drops whole day partitions - message, payload and delivery together - older than 30 days, keeps a
 * day holding a dead-lettered or non-final delivery (any tenant's, an inactive tenant's included), never touches a day
 * inside the retention, and only the maintenance role can run it.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PayloadPurgeContainerTest.GrantAppRoleWiring.class)
class PayloadPurgeContainerTest {

    /** The app login joins ipaas_app right after Flyway: the application reads operator_allowed_host at start. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRoleWiring {
        @Bean
        Callback grantAppRoleFlywayCallback() {
            return new GrantAppRoleFlywayCallback(POSTGRES, login);
        }
    }


    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID ACTIVE = UUID.fromString("0000000a-0000-4000-8000-0000000000d1");
    private static final UUID SUSPENDED = UUID.fromString("0000000b-0000-4000-8000-0000000000d2");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String MAINT_LOGIN = "maint_login_test";
    private static final String MAINT_PASSWORD = "maint-" + UUID.randomUUID();

    private static PlatformTestWiring.RestrictedLogin login;
    private static String preparedFor;
    private static JdbcPartitionPurge maintenance;

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private final LocalDate clean = today.minusDays(45);
    private final LocalDate deadLetter = today.minusDays(44);
    private final LocalDate nonFinal = today.minusDays(43);
    private final LocalDate suspendedDeadLetter = today.minusDays(42);
    private final LocalDate routeFailed = today.minusDays(41);
    private final LocalDate accepted = today.minusDays(40);
    private final LocalDate onTheCutoff = today.minusDays(30);
    private final LocalDate young = today.minusDays(10);
    private final LocalDate cutoff = today.minusDays(30);

    @Autowired
    private JdbcTemplate application;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @AfterAll
    static void closePool() {
        if (maintenance != null) {
            maintenance.close();
        }
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            if (!POSTGRES.getJdbcUrl().equals(preparedFor)) {
                preparedFor = POSTGRES.getJdbcUrl();
                sql.execute("create role " + MAINT_LOGIN + " login nosuperuser nobypassrls password '" + MAINT_PASSWORD
                        + "' in role ipaas_maintenance");
                sql.execute("insert into public.tenant (id, code, status, time_zone) values ('" + ACTIVE
                        + "', 'purge-active', 'ACTIVE', 'Etc/UTC'), ('" + SUSPENDED
                        + "', 'purge-suspended', 'SUSPENDED', 'Etc/UTC') on conflict (id) do nothing");
                maintenance = JdbcPartitionPurge.pooled(POSTGRES.getJdbcUrl(), MAINT_LOGIN, MAINT_PASSWORD);
            }
            seedDay(owner, clean, ACTIVE, "DELIVERED", "ROUTED");
            seedDay(owner, deadLetter, ACTIVE, "DEAD_LETTERED", "ROUTED");
            seedDay(owner, nonFinal, ACTIVE, "PENDING", "ROUTED");
            seedDay(owner, suspendedDeadLetter, SUSPENDED, "DEAD_LETTERED", "ROUTED");
            seedDay(owner, routeFailed, ACTIVE, null, "ROUTE_FAILED");
            seedDay(owner, accepted, ACTIVE, null, "ACCEPTED");
            seedDay(owner, onTheCutoff, ACTIVE, "DELIVERED", "ROUTED");
            seedDay(owner, young, ACTIVE, "DELIVERED", "ROUTED");
        }
    }

    /** One day's three partitions with one row each; recreated if an earlier test dropped them. */
    private void seedDay(Connection owner, LocalDate day, UUID tenant, String deliveryState,
            String messageState) throws SQLException {
        String suffix = day.format(DAY);
        try (Statement sql = owner.createStatement()) {
            for (String table : new String[] {"message", "payload", "delivery"}) {
                sql.execute("create table if not exists public." + table + "_" + suffix + " partition of public." + table
                        + " for values from ('" + day + "') to ('" + day.plusDays(1) + "')");
            }
            sql.execute("delete from public.delivery_" + suffix);
            sql.execute("delete from public.payload_" + suffix);
            sql.execute("delete from public.message_" + suffix);
        }
        UUID message = UUID.randomUUID();
        String at = day + "T12:00:00Z";
        try (PreparedStatement st = owner.prepareStatement("insert into public.message (id, tenant_id, interface_id,"
                + " interface_version_id, source_contract_version_id, business_key, state, accepted_at)"
                + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'k', ?, ?::timestamptz)")) {
            st.setString(1, message.toString());
            st.setString(2, tenant.toString());
            for (int i = 3; i <= 5; i++) {
                st.setString(i, UUID.randomUUID().toString());
            }
            st.setString(6, messageState);
            st.setString(7, at);
            st.executeUpdate();
        }
        try (PreparedStatement st = owner.prepareStatement("insert into public.payload (message_id, tenant_id,"
                + " accepted_at, body) values (?::uuid, ?::uuid, ?::timestamptz, '{\"a\":1}')")) {
            st.setString(1, message.toString());
            st.setString(2, tenant.toString());
            st.setString(3, at);
            st.executeUpdate();
        }
        if (deliveryState == null) {
            return;
        }
        try (PreparedStatement st = owner.prepareStatement("insert into public.delivery (tenant_id, state, created_at)"
                + " values (?::uuid, ?, ?::timestamptz)")) {
            st.setString(1, tenant.toString());
            st.setString(2, deliveryState);
            st.setString(3, at);
            st.executeUpdate();
        }
    }

    private boolean exists(String table, LocalDate day) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement query = owner.prepareStatement("select to_regclass(?) is not null")) {
            query.setString(1, "public." + table + "_" + day.format(DAY));
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private boolean wholeDay(LocalDate day) throws SQLException {
        return exists("message", day) && exists("payload", day) && exists("delivery", day);
    }

    private boolean nothingOfDay(LocalDate day) throws SQLException {
        return !exists("message", day) && !exists("payload", day) && !exists("delivery", day);
    }

    @Test
    @Tag("negative")
    void aCleanDayIsDroppedWholeAndEveryDayWithADeadLetterOrNonFinalDeliverySurvives() throws SQLException {
        PartitionPurge.Outcome outcome = maintenance.purgeBefore(cutoff, false);

        assertThat(nothingOfDay(clean)).as("clean day: message, payload and delivery all dropped").isTrue();
        assertThat(wholeDay(deadLetter)).as("1 dead letter: whole day kept, replayable").isTrue();
        assertThat(wholeDay(nonFinal)).as("1 non-final delivery: whole day kept").isTrue();
        assertThat(wholeDay(suspendedDeadLetter)).as("a suspended tenant's dead letter still guards its day").isTrue();
        assertThat(wholeDay(routeFailed)).as("a ROUTE_FAILED message: we cannot prove it final, day kept").isTrue();
        assertThat(wholeDay(accepted)).as("a never-routed ACCEPTED message: day kept").isTrue();
        assertThat(wholeDay(onTheCutoff)).as("the cutoff day itself is inside the 30 days: kept").isTrue();
        assertThat(wholeDay(young)).as("10 days old: untouched").isTrue();
        assertThat(outcome.dropped()).contains(clean).doesNotContain(deadLetter, nonFinal, onTheCutoff, young);
        assertThat(outcome.kept()).contains(deadLetter, nonFinal, suspendedDeadLetter, routeFailed, accepted);
        assertThat(outcome.dryRun()).isFalse();
    }

    @Test
    void aDryRunReportsWhatWouldDropAndDropsNothing() throws SQLException {
        PartitionPurge.Outcome outcome = maintenance.purgeBefore(cutoff, true);

        assertThat(outcome.dryRun()).isTrue();
        assertThat(outcome.dropped()).contains(clean);
        assertThat(outcome.kept()).contains(deadLetter, nonFinal, suspendedDeadLetter, routeFailed, accepted);
        assertThat(wholeDay(clean)).as("dry run: still there").isTrue();
    }

    @Test
    @Tag("negative")
    void aCutoffInsideTheThirtyDayRetentionIsRefusedAndNothingIsDropped() throws SQLException {
        assertThat(RetentionPolicy.AGREED.payload()).isEqualTo(java.time.Period.ofDays(30));
        assertThatThrownBy(() -> maintenance.purgeBefore(today.minusDays(29), false))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("inside the 30-day retention");
        assertThat(wholeDay(clean)).isTrue();
        assertThat(wholeDay(young)).isTrue();
    }

    @Test
    @Tag("negative")
    void theApplicationRoleCannotRunThePurgeOrDropAPartition() throws SQLException {
        assertThatThrownBy(() -> application.queryForList("select * from public.payload_purge_days(?, true)", cutoff))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for function payload_purge_days");
        assertThatThrownBy(() -> application.execute("drop table public.message_" + clean.format(DAY)))
                .isInstanceOf(DataAccessException.class);
        assertThat(wholeDay(clean)).isTrue();
    }

    @Test
    @Tag("negative")
    void theMaintenanceRoleHoldsNoPowerBeyondTheOneFunction() throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), MAINT_LOGIN, MAINT_PASSWORD);
                Statement sql = c.createStatement()) {
            assertThatThrownBy(() -> sql.execute("drop table public.message_" + clean.format(DAY)))
                    .as("not the owner: no raw DDL").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeQuery("select count(*) from public.payload"))
                    .as("no read of tenant data").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.execute("delete from public.delivery")).isInstanceOf(SQLException.class);
        }
        assertThat(wholeDay(clean)).isTrue();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                ResultSet rs = owner.createStatement().executeQuery("select rolbypassrls or rolsuper or rolcreatedb"
                        + " or rolcreaterole or rolcanlogin from pg_roles where rolname = 'ipaas_maintenance'")) {
            rs.next();
            assertThat(rs.getBoolean(1)).as("ipaas_maintenance itself: NOLOGIN, no extra power").isFalse();
        }
    }

    @Test
    void theFunctionRunsAsTheTableOwnerWithAFixedSearchPathAndLockTimeout() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                ResultSet rs = owner.createStatement().executeQuery("select count(*) from pg_proc p"
                        + " join pg_class t on t.oid = 'public.delivery'::regclass"
                        + " where p.oid = 'public.payload_purge_days(date, boolean)'::regprocedure and p.prosecdef"
                        + " and p.proowner = t.relowner and 'search_path=pg_catalog, pg_temp' = any (p.proconfig)"
                        + " and 'lock_timeout=5s' = any (p.proconfig)"
                        + " and not has_function_privilege('ipaas_app', p.oid, 'EXECUTE')"
                        + " and has_function_privilege('ipaas_maintenance', p.oid, 'EXECUTE')")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
