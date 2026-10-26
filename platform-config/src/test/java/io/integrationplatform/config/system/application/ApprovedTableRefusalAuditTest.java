package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository.ApprovedTableRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L19 (Karan ruling 2026-10-23, section 2): a post-authorisation, post-state refusal of a DB-table approval - the
 * account is too wide or cannot be verified, or the target changed under the decision - writes ONE durable
 * {@code DB_TABLE_APPROVAL_REFUSED} event in its own short transaction (not rolled back with the refusal), throttled
 * to one per (approval row, refusal code) per hour, carrying a closed code and fixed-vocabulary tokens and never a
 * host, account name, database name, SQL or driver text.
 */
class ApprovedTableRefusalAuditTest {

    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID REQUESTER = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID APPROVER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String SYSTEM = "88888888-8888-8888-8888-888888888888";
    private static final String ROW = "55555555-5555-5555-5555-555555555555";
    private static final String WIDE = "DB_ACCOUNT_TOO_WIDE: the account \"svc_secret_login\" holds more than INSERT and"
            + " SELECT on \"orders_in\" or holds account-level or other-table rights (DELETE, SUPERUSER,"
            + " PG_WRITE_SERVER_FILES, drop table x, host=10.1.2.3); grant the restricted account only INSERT and SELECT";

    private final JdbcApprovedTableRepository tables = mock(JdbcApprovedTableRepository.class);
    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final DbAccountWidthCheck width = mock(DbAccountWidthCheck.class);
    private final List<Object> events = new ArrayList<>();
    private final List<String> trail = new ArrayList<>();
    private Instant now = Instant.parse("2026-10-23T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private ApprovedTableService service;

    /** Records begin/commit/rollback so a test can see the refusal event is committed on its own. */
    private final class RecordingManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { trail.add("begin"); }
        @Override protected void doCommit(DefaultTransactionStatus status) { trail.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { trail.add("rollback"); }
    }

    @BeforeEach
    void wire() {
        service = new ApprovedTableService(tables, systems, event -> {
            trail.add("publish");
            events.add(event);
        }, () -> Optional.of(TENANT), width, new TransactionTemplate(new RecordingManager()), clock);
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(
                new SystemDetail(SYSTEM, "orders-db", "Orders DB", "TARGET", "DB", "{}")));
        when(tables.find(ROW)).thenReturn(Optional.of(new ApprovedTableRow(ROW, SYSTEM, "orders_in",
                "PENDING_APPROVAL", REQUESTER.toString(), Instant.now(), null, null, true)));
    }

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    private void approveRefused() {
        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
    }

    @Test
    @Tag("security")
    @DisplayName("A wide account refusal writes one DB_TABLE_APPROVAL_REFUSED event with the closed code and only vocabulary tokens")
    void aWideAccountWritesOneRefusalEvent() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));

        approveRefused();

        assertThat(audits()).hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.DB_TABLE_APPROVAL_REFUSED)
                .isEqualTo("DB_TABLE_APPROVAL_REFUSED");
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(APPROVER.toString());
        assertThat(event.targetIds()).containsExactly("system:" + SYSTEM, "db_table_approval:" + ROW);
        assertThat(event.before()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
        assertThat(event.after()).contains("\"status\":\"PENDING_APPROVAL\"")
                .contains("\"refusal\":\"DB_ACCOUNT_TOO_WIDE\"").contains("\"table\":\"orders_in\"")
                .contains("\"tokens\":[\"DELETE\",\"SUPERUSER\",\"PG_WRITE_SERVER_FILES\"]");
    }

    @Test
    @Tag("security")
    @DisplayName("Nothing but the closed code, the table and vocabulary tokens reaches the event: no account, host, SQL or driver text")
    void noFreeTextReachesTheEvent() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));

        approveRefused();

        AuditEvent event = audits().getFirst();
        String all = event.before() + event.after() + event.targetIds();
        assertThat(all).doesNotContain("svc_secret_login").doesNotContain("10.1.2.3").doesNotContain("drop table")
                .doesNotContain("grant the restricted");
    }

    @Test
    @Tag("security")
    @DisplayName("An unverifiable account (probe failed) writes DB_ACCOUNT_UNVERIFIED with no tokens and no exception text")
    void anUnverifiedAccountWritesTheUnverifiedCode() {
        when(width.refusalFor(any(), anyString()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/db"));

        approveRefused();

        assertThat(audits()).hasSize(1);
        String after = audits().getFirst().after();
        assertThat(after).contains("\"refusal\":\"DB_ACCOUNT_UNVERIFIED\"").doesNotContain("tokens")
                .doesNotContain("secret-host").doesNotContain("jdbc");
    }

    @Test
    @DisplayName("The event is committed in its own short transaction, before the refusal is rethrown")
    void theEventIsItsOwnCommittedTransaction() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));

        approveRefused();

        // transaction 1 (read) commits, no transaction around the probe, then the refusal event begins, publishes, commits
        assertThat(trail).containsExactly("begin", "commit", "begin", "publish", "commit");
    }

    @Test
    @DisplayName("Throttle: the same (approval row, code) writes one event per hour; a different code or the next hour writes again")
    void oneEventPerRowAndCodePerHour() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));

        approveRefused();
        approveRefused();
        now = now.plus(Duration.ofMinutes(59));
        approveRefused();
        assertThat(audits()).as("three identical refusals inside the hour").hasSize(1);

        when(width.refusalFor(any(), anyString()))
                .thenReturn(Optional.of("DB_ACCOUNT_UNVERIFIED: the probe could not run"));
        approveRefused();
        assertThat(audits()).as("another code is its own throttle key").hasSize(2);

        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));
        now = now.plus(Duration.ofMinutes(2));
        approveRefused();
        assertThat(audits()).as("an hour after the first write the same code is audited again").hasSize(3);
    }

    @Test
    @DisplayName("A failing audit write never turns the 409 refusal into a 500")
    void aFailingAuditKeepsTheRefusal() {
        ApprovedTableService failing = new ApprovedTableService(tables, systems, event -> {
            throw new IllegalStateException("audit down");
        }, () -> Optional.of(TENANT), width, TransactionOperations.withoutTransaction(), clock);
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of(WIDE));

        assertThatThrownBy(() -> failing.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
    }

    @Test
    @DisplayName("TARGET_CHANGED out of the decision transaction is audited the same way")
    void targetChangedIsAudited() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.empty());
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenReturn("REFUSED_TARGET_CHANGED");

        approveRefused();

        assertThat(audits()).hasSize(1);
        assertThat(audits().getFirst().after()).contains("\"refusal\":\"TARGET_CHANGED\"");
    }

    @Test
    @DisplayName("Not audited here: a self-approval (REQUESTER_IS_APPROVER) and an unknown row")
    void otherRefusalsAreNotThisEvent() {
        when(tables.decide(ROW, REQUESTER.toString(), "APPROVE")).thenReturn("REFUSED_SELF_APPROVAL");

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", REQUESTER))
                .isInstanceOf(ConfigApiException.class);
        assertThatThrownBy(() -> service.decide(SYSTEM, UUID.randomUUID().toString(), "APPROVE", APPROVER))
                .isInstanceOf(ConfigApiException.class);

        assertThat(audits()).isEmpty();
    }
}
