package io.integrationplatform.app.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row327 T118 (E12-F2-S3, T-135): the audit purge drops whole month partitions older than the 7-year retention and
 * nothing younger, and the application role can neither run it nor drop a partition itself (architecture.md control
 * T6, ADR-011 Decision 8, ADR-016: the app role runs no DDL; the maintenance role that will call it is T-135b).
 *
 * <pre>
 * &#64;negative
 * Scenario: Audit younger than 7 years is never purged
 *   Given audit partitions aged 6 years 11 months and 7 years 1 month
 *   When the audit purge runs
 *   Then only the older partition is dropped
 * </pre>
 *
 * <p>The two aged partitions are created through the owner connection (the migration owner keeps the partition DDL);
 * the purge runs as that owner, and the application's own restricted login is refused EXECUTE. Written in
 * row 327 lane t118 and NOT run there (no Docker in the lane); the lead runs it in a docker-suite lock slot.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditRetentionContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-0000000000c1");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private static PlatformTestWiring.RestrictedLogin login;
    private static boolean granted;

    /** The month that starts 7 years 1 month ago: every row in it is older than 7 years. */
    private final LocalDate expired = LocalDate.now(ZoneOffset.UTC).minusYears(7).minusMonths(1).withDayOfMonth(1);
    /** The month that starts 6 years 11 months ago: every row in it is younger than 7 years. */
    private final LocalDate kept = LocalDate.now(ZoneOffset.UTC).minusYears(6).minusMonths(11).withDayOfMonth(1);

    @Autowired
    private JdbcTemplate application;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @BeforeEach
    void agedPartitionsWithOneRowEach() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            if (!granted) {
                granted = true;
                PlatformTestWiring.grantApplicationRole(POSTGRES, login);
                sql.execute("insert into public.tenant (id, code, status, time_zone) values ('" + TENANT
                        + "', 'retention-tenant', 'ACTIVE', 'Etc/UTC') on conflict (id) do nothing");
            }
            for (LocalDate month : new LocalDate[] {expired, kept}) {
                sql.execute("create table if not exists public." + partition(month)
                        + " partition of public.audit_event for values from ('" + month + "') to ('"
                        + month.plusMonths(1) + "')");
                try (PreparedStatement row = owner.prepareStatement("insert into public.audit_event"
                        + " (tenant_id, actor, action, event_at, created_at) values (?::uuid, 'retention', ?, ?, ?)")) {
                    row.setString(1, TENANT.toString());
                    row.setString(2, "AGED_" + month);
                    row.setObject(3, month.plusDays(14).atStartOfDay().atOffset(ZoneOffset.UTC));
                    row.setObject(4, month.plusDays(14).atStartOfDay().atOffset(ZoneOffset.UTC));
                    row.executeUpdate();
                }
            }
        }
    }

    private static String partition(LocalDate month) {
        return "audit_event_" + month.format(MONTH);
    }

    private boolean exists(String table) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement query = owner.prepareStatement("select to_regclass(?) is not null")) {
            query.setString(1, "public." + table);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Runs the purge as the migration owner - the only role that may today. */
    private int purgeAsOwner() throws SQLException {
        return ownerCount("select public.audit_purge_expired()");
    }

    private int ownerCount(String sql) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement query = owner.createStatement(); ResultSet rs = query.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Tag("negative")
    void onlyThePartitionOlderThanSevenYearsIsDropped() throws SQLException {
        String currentMonth = partition(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));

        int dropped = purgeAsOwner();

        assertThat(dropped).as("exactly one partition is past the 7-year retention").isEqualTo(1);
        assertThat(exists(partition(expired))).as("aged 7 years 1 month: dropped").isFalse();
        assertThat(exists(partition(kept))).as("aged 6 years 11 months: kept").isTrue();
        assertThat(exists(currentMonth)).as("this month's live partition: kept").isTrue();
        assertThat(ownerCount("select count(*) from public.audit_event where action = 'AGED_" + kept + "'"))
                .as("the kept partition's rows are still there").isPositive();
        assertThat(ownerCount("select count(*) from public.audit_event where action = 'AGED_" + expired + "'"))
                .as("the dropped partition's row went with it").isZero();
    }

    @Test
    void aSecondRunFindsNothingMoreToDrop() throws SQLException {
        purgeAsOwner();

        assertThat(purgeAsOwner()).isZero();
    }

    @Test
    @Tag("negative")
    void theApplicationRoleHasNoExecuteOnThePurgeAndIsRefused() throws SQLException {
        assertThat(ownerCount("select count(*) where has_function_privilege('ipaas_app',"
                + " 'public.audit_purge_expired()', 'EXECUTE')"))
                .as("ipaas_app holds 0 EXECUTE on the purge")
                .isZero();
        assertThatThrownBy(() -> application.queryForObject("select public.audit_purge_expired()", Integer.class))
                .as("the application's own login is refused: permission denied")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("permission denied");
        assertThat(exists(partition(expired))).as("nothing was dropped by the refused call").isTrue();
    }

    @Test
    @Tag("negative")
    void theApplicationRoleCannotDropOrEmptyAPartitionItself() throws SQLException {
        assertThatThrownBy(() -> application.execute("drop table public." + partition(kept)))
                .as("partition DDL stays with the migration owner")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application.execute("truncate public." + partition(kept)))
                .isInstanceOf(DataAccessException.class);
        assertThat(exists(partition(kept))).isTrue();
    }

    @Test
    void thePurgeRunsAsTheTableOwnerWithAFixedSearchPathAndLockTimeout() throws SQLException {
        assertThat(ownerCount("select count(*) from pg_proc p join pg_class t on t.oid = 'public.audit_event'::regclass"
                + " where p.oid = 'public.audit_purge_expired()'::regprocedure and p.prosecdef"
                + " and p.proowner = t.relowner"
                + " and 'search_path=pg_catalog, pg_temp' = any (p.proconfig)"
                + " and 'lock_timeout=5s' = any (p.proconfig)"))
                .as("SECURITY DEFINER, owned by the audit table's owner, search_path and lock_timeout pinned")
                .isEqualTo(1);
    }
}
