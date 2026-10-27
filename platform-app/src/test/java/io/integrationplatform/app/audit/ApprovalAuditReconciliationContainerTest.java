package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 544 (option A, R1): {@code deploy/hostinger/audit/reconcile-self-approved.sql} - the documented, read-only check
 * that the {@code selfApproved} flag of every approval audit event agrees with the business row. The business rows
 * (flow version, contract version, DB-table approval) and their ledger events are seeded as the owner (fixtures
 * only); the query is the file itself, run unchanged. Agreeing events (self-approved true, approved by another false)
 * report nothing; a seeded lying event, a missing flag and a vanished business row are each reported.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalAuditReconciliationContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    private static final UUID SYSTEM = UUID.randomUUID();
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID FLOW = UUID.randomUUID();
    private static final UUID SOURCE_CONTRACT_VERSION = UUID.randomUUID();
    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();
    private static int flowNumber;
    private static int contractLabel;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @BeforeEach
    void seed() throws SQLException {
        if (!SEEDED.compareAndSet(false, true)) {
            clearLedger(); // each test starts from an empty ledger (the owner may delete; the app role may not)
            return;
        }
        AuditContainerSupport.seed(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.system (id, tenant_id, code, name, direction) values ('" + SYSTEM + "', '"
                    + TENANT_A + "', 'REC544', 'rec', 'BOTH')");
            sql.execute("insert into public.contract (id, tenant_id, system_id, code, role, name) values ('" + CONTRACT
                    + "', '" + TENANT_A + "', '" + SYSTEM + "', 'REC544', 'SOURCE_REQUEST', 'c')");
            sql.execute("insert into public.interface (id, tenant_id, code, name, mode) values ('" + FLOW + "', '"
                    + TENANT_A + "', 'REC544', 'f', 'ASYNC')");
            contractVersion(sql, SOURCE_CONTRACT_VERSION, U1, U1);
        }
    }

    private static void clearLedger() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("delete from public.audit_event");
        }
    }

    private static void contractVersion(Statement sql, UUID id, UUID author, UUID approver) throws SQLException {
        sql.execute("insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                + " json_schema_draft_2020_12, author_id) values ('" + id + "', '" + TENANT_A + "', '" + CONTRACT
                + "', '" + (++contractLabel) + ".0', 'DRAFT', '{\"type\":\"object\"}'::jsonb, '" + author + "')");
        sql.execute("update public.contract_version set status = 'PENDING_APPROVAL' where id = '" + id + "'");
        sql.execute("update public.contract_version set status = 'APPROVED', approver_id = '" + approver
                + "' where id = '" + id + "'");
    }

    private UUID flowVersion(UUID author, UUID approver) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.interface_version (id, tenant_id, interface_id, version, business_key_rule,"
                    + " source_contract_version_id, author_id, snapshot) values ('" + id + "', '" + TENANT_A + "', '"
                    + FLOW + "', " + (++flowNumber) + ", 'k', '" + SOURCE_CONTRACT_VERSION + "', '" + author
                    + "', '{}'::jsonb)");
            sql.execute("update public.interface_version set status = 'PENDING_APPROVAL', change_note = 'r' where id = '"
                    + id + "'");
            sql.execute("update public.interface_version set status = 'APPROVED', approver_id = '" + approver
                    + "', decided_at = now() where id = '" + id + "'");
        }
        return id;
    }

    private UUID contractApproval(UUID author, UUID approver) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            contractVersion(sql, id, author, approver);
        }
        return id;
    }

    private UUID tableApproval(String requestedBy, String decidedBy) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("alter table public.db_target_approved_table drop constraint if exists"
                    + " db_target_approved_table_four_eyes"); // single-user workflow (V2_4_1420) already dropped it
            sql.execute("insert into public.db_target_approved_table (id, tenant_id, system_id, table_name, status,"
                    + " requested_by, decided_by, decided_at, target_hash) values ('" + id + "', '" + TENANT_A + "', '"
                    + SYSTEM + "', 't_" + id.toString().substring(0, 8) + "', 'APPROVED', '" + requestedBy + "', '"
                    + decidedBy + "', now(), 'h')");
        }
        return id;
    }

    private void event(UUID tenant, String action, String targetKind, UUID target, String afterState)
            throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                PreparedStatement insert = owner.prepareStatement("insert into public.audit_event (tenant_id, actor,"
                        + " action, target_ids, after_state, event_at) values (?::uuid, 'rec-actor', ?,"
                        + " jsonb_build_array(?), ?, now())")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, action);
            insert.setString(3, targetKind + ":" + target);
            insert.setString(4, afterState);
            insert.executeUpdate();
        }
    }

    private static String claim(boolean selfApproved) {
        return "{\"status\":\"APPROVED\",\"selfApproved\":" + selfApproved + "}";
    }

    /** Runs the documented query file, unchanged, bound to {@code tenant}; one string per reported row. */
    private List<String> reconcile(UUID tenant) throws Exception {
        Path file = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize().getParent()
                .resolve("deploy/hostinger/audit/reconcile-self-approved.sql");
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        List<String> rows = new ArrayList<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement statement =
                owner.createStatement()) {
            statement.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(rs.getString("kind") + "|" + rs.getString("target_id") + "|"
                            + rs.getString("claimed_self_approved") + "|" + rs.getString("actual_self_approved"));
                }
            }
        }
        return rows;
    }

    @Test
    void cleanDataReportsNothingForFlowContractAndDbTableApprovals() throws Exception {
        UUID selfFlow = flowVersion(U1, U1);
        UUID otherFlow = flowVersion(U1, U2);
        UUID selfContract = contractApproval(U1, U1);
        UUID otherContract = contractApproval(U1, U2);
        UUID selfTable = tableApproval(U1.toString(), U1.toString());
        UUID otherTable = tableApproval(U1.toString(), U2.toString());
        event(TENANT_A, "FLOW_APPROVED", "interface_version", selfFlow, claim(true));
        event(TENANT_A, "FLOW_APPROVED", "interface_version", otherFlow, claim(false));
        event(TENANT_A, "CONTRACT_VERSION_APPROVED", "contract_version", selfContract, claim(true));
        event(TENANT_A, "CONTRACT_VERSION_APPROVED", "contract_version", otherContract, claim(false));
        event(TENANT_A, "DB_TABLE_APPROVED", "db_table_approval", selfTable, claim(true));
        event(TENANT_A, "DB_TABLE_APPROVED", "db_table_approval", otherTable, claim(false));

        assertThat(reconcile(TENANT_A)).as("every claim agrees with its business row").isEmpty();
    }

    @Test
    @Tag("negative")
    void aSeededLyingEventAMissingFlagAndAVanishedRowAreEachReported() throws Exception {
        UUID liedFlow = flowVersion(U1, U2); // approved by another user ...
        event(TENANT_A, "FLOW_APPROVED", "interface_version", liedFlow, claim(true)); // ... event says self
        UUID liedContract = contractApproval(U1, U1); // self-approved ...
        event(TENANT_A, "CONTRACT_VERSION_APPROVED", "contract_version", liedContract, claim(false)); // ... says other
        UUID liedTable = tableApproval(U1.toString(), U2.toString());
        event(TENANT_A, "DB_TABLE_APPROVED", "db_table_approval", liedTable, claim(true));
        UUID noFlag = flowVersion(U1, U1);
        event(TENANT_A, "FLOW_APPROVED", "interface_version", noFlag, "{\"status\":\"APPROVED\"}");
        UUID vanished = UUID.randomUUID();
        event(TENANT_A, "FLOW_APPROVED", "interface_version", vanished, claim(false));

        List<String> reported = reconcile(TENANT_A);

        assertThat(reported).hasSize(5)
                .anyMatch(r -> r.equals("FLOW_APPROVED|" + liedFlow + "|t|f"))
                .anyMatch(r -> r.equals("CONTRACT_VERSION_APPROVED|" + liedContract + "|f|t"))
                .anyMatch(r -> r.equals("DB_TABLE_APPROVED|" + liedTable + "|t|f"))
                .anyMatch(r -> r.startsWith("FLOW_APPROVED|" + noFlag + "|null|"))
                .anyMatch(r -> r.startsWith("FLOW_APPROVED|" + vanished + "|f|"));
    }

    @Test
    void anotherTenantsRunSeesNothingOfThisTenantsEvents() throws Exception {
        UUID lied = flowVersion(U1, U2);
        event(TENANT_A, "FLOW_APPROVED", "interface_version", lied, claim(true));

        assertThat(reconcile(TENANT_B)).isEmpty();
    }

    @Test
    @Tag("negative")
    void withNoTenantBoundTheCheckFailsInsteadOfReadingEverything() throws Exception {
        Path file = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize().getParent()
                .resolve("deploy/hostinger/audit/reconcile-self-approved.sql");
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement statement =
                owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery(sql)).isInstanceOf(SQLException.class);
        }
    }
}
