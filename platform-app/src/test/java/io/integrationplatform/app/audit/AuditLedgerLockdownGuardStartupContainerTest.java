package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.IntegrationPlatformApplication;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.app.scheduler.GrantAppRoleFlywayCallback;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 570 (L3): the real application, started as the restricted login, refuses to start in DEFINER mode while the
 * audit ledger lockdown of V2_4_1490 / V2_4_1540 is open again (a manual re-grant, a restore from an old dump, a
 * half-run rollback) - on the ledger, on one of its partitions, or on {@code audit_outbox.relayed_at}. With the lockdown
 * intact it starts; in LEGACY mode (the documented rollback) the check is skipped. One database for the class: each
 * case re-grants one privilege, boots, and revokes it again. Container group.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLedgerLockdownGuardStartupContainerTest {

    private static final String REFUSAL = "audit ledger lockdown is open";

    private PostgreSQLContainer postgres;
    private PlatformTestWiring.RestrictedLogin login;
    private Map<String, Object> properties;

    @Configuration(proxyBeanMethods = false)
    static class GrantAppRole {

        static PostgreSQLContainer container;
        static PlatformTestWiring.RestrictedLogin restricted;

        @Bean
        Callback grantAppRole() {
            return new GrantAppRoleFlywayCallback(container, restricted);
        }
    }

    @BeforeAll
    void startDatabase() {
        postgres = new PostgreSQLContainer(TestImages.POSTGRES);
        postgres.start();
        Map<String, Object> raw = new HashMap<>();
        login = PlatformTestWiring.register(raw::put, postgres);
        raw.put("server.port", "0");
        properties = new HashMap<>();
        raw.forEach((name, value) -> properties.put(name, value instanceof Supplier<?> s ? s.get() : value));
        GrantAppRole.container = postgres;
        GrantAppRole.restricted = login;
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private void boot(String relayMode) {
        Map<String, Object> run = new HashMap<>(properties);
        if (relayMode != null) {
            run.put("ipaas.audit.relay.mode", relayMode);
        }
        new SpringApplicationBuilder(IntegrationPlatformApplication.class, GrantAppRole.class).properties(run).run()
                .close();
    }

    private void asOwner(String... statements) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres); Statement sql = owner.createStatement()) {
            for (String statement : statements) {
                sql.execute(statement);
            }
        }
    }

    private String currentPartition() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(postgres); Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery("select i.inhrelid::regclass::text from pg_inherits i"
                        + " where i.inhparent = 'public.audit_event'::regclass order by 1 limit 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void refusedWhileGranted(String grant, String revoke, String objectInMessage) throws SQLException {
        asOwner(grant);
        try {
            assertThatThrownBy(() -> boot(null)).hasStackTraceContaining(REFUSAL)
                    .hasStackTraceContaining(objectInMessage);
        } finally {
            asOwner(revoke);
        }
    }

    @Test
    void withTheLockdownIntactTheApplicationStarts() {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
    }

    @Test
    @Tag("negative")
    void aReGrantedLedgerInsertStopsTheStart() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException(); // also migrates this database
        refusedWhileGranted("grant insert on public.audit_event to ipaas_app",
                "revoke insert on public.audit_event from ipaas_app", "INSERT on public.audit_event");
    }

    @Test
    @Tag("negative")
    void aReGrantedInsertOnAMonthPartitionStopsTheStart() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
        String partition = currentPartition();
        refusedWhileGranted("grant insert on " + partition + " to ipaas_app",
                "revoke insert on " + partition + " from ipaas_app", "INSERT on public." + partition.replace("public.", ""));
    }

    @Test
    @Tag("negative")
    void aReGrantedRelayedAtUpdateStopsTheStart() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
        refusedWhileGranted("grant update (relayed_at) on public.audit_outbox to ipaas_app",
                "revoke update (relayed_at) on public.audit_outbox from ipaas_app",
                "UPDATE (relayed_at) on public.audit_outbox");
    }

    @Test
    @Tag("negative")
    void aReGrantedRelayedAtInsertStopsTheStart() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
        refusedWhileGranted("grant insert (relayed_at) on public.audit_outbox to ipaas_app",
                "revoke insert (relayed_at) on public.audit_outbox from ipaas_app",
                "INSERT (relayed_at) on public.audit_outbox");
    }

    @Test
    void legacyModeSkipsTheCheckBecauseItIsTheDocumentedRollbackPath() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
        asOwner("grant insert on public.audit_event to ipaas_app");
        try {
            assertThatCode(() -> boot("LEGACY")).doesNotThrowAnyException();
        } finally {
            asOwner("revoke insert on public.audit_event from ipaas_app");
        }
    }

    /** Row 570 (M1): the hosted compose gives the app no owner login and turns in-app Flyway off; deploy.sh migrated first. */
    @Test
    void theApplicationStartsWithoutTheOwnerCredentialsOnceTheDatabaseIsMigratedBeforehand() {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException(); // the deploy.sh stand-in: migrates this database
        Map<String, Object> run = new HashMap<>(properties);
        run.remove("DB_MIGRATION_USERNAME");
        run.remove("DB_MIGRATION_PASSWORD");
        run.put("spring.flyway.enabled", "false");

        assertThatCode(() -> new SpringApplicationBuilder(IntegrationPlatformApplication.class, GrantAppRole.class)
                .properties(run).run().close()).doesNotThrowAnyException();
    }

    /** Karan LOW (row 570): the guard names every write privilege the lockdown took away, not only the ledger INSERT. */
    @Test
    @Tag("negative")
    void anyReGrantedWritePrivilegeOnTheLedgerOrTheOutboxStopsTheStart() throws SQLException {
        assertThatCode(() -> boot(null)).doesNotThrowAnyException();
        String[][] cases = {
            {"update on public.audit_event", "UPDATE on public.audit_event"},
            {"delete on public.audit_event", "DELETE on public.audit_event"},
            {"truncate on public.audit_event", "TRUNCATE on public.audit_event"},
            {"update (actor) on public.audit_outbox", "UPDATE (actor) on public.audit_outbox"},
            {"update on public.audit_outbox", "UPDATE on public.audit_outbox"},
            {"delete on public.audit_outbox", "DELETE on public.audit_outbox"},
            {"truncate on public.audit_outbox", "TRUNCATE on public.audit_outbox"},
        };
        for (String[] c : cases) {
            refusedWhileGranted("grant " + c[0] + " to ipaas_app", "revoke " + c[0] + " from ipaas_app", c[1]);
        }
    }
}
