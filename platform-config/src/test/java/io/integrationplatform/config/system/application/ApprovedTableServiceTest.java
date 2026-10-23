package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository.ApprovedTableRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 F3 (design note 2026-10-22, option B; D1 tenant approver, D4 no self-approval): the approval service of a DB
 * target's tables. A request creates a PENDING row and one audit event; a decision goes through the database function
 * (the repository mock answers its codes); a refusal publishes no audit event (it rolls back; the controller logs it).
 */
class ApprovedTableServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID REQUESTER = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID APPROVER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String SYSTEM = "88888888-8888-8888-8888-888888888888";
    private static final String OTHER_SYSTEM = "11111111-1111-1111-1111-111111111111";
    private static final String ROW = "55555555-5555-5555-5555-555555555555";

    private final JdbcApprovedTableRepository tables = mock(JdbcApprovedTableRepository.class);
    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final DbAccountWidthCheck width = mock(DbAccountWidthCheck.class);
    private final ApprovedTableService service = new ApprovedTableService(tables, systems, events::add,
            () -> Optional.of(TENANT), width, TransactionOperations.withoutTransaction());

    @org.junit.jupiter.api.BeforeEach
    void narrowAccountByDefault() {
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.empty());
    }

    private static SystemDetail system(String type) {
        return new SystemDetail(SYSTEM, "orders-db", "Orders DB", "TARGET", type, "{}");
    }

    private static ApprovedTableRow row(String status, String systemId) {
        return new ApprovedTableRow(ROW, systemId, "orders_in", status, REQUESTER.toString(), Instant.now(), null,
                null, true);
    }

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    private AuditEvent theOneAudit(String action) {
        assertThat(audits()).as("exactly one audit event for the completed action").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.action()).isEqualTo(action);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.targetIds()).contains("system:" + SYSTEM);
        return event;
    }

    @Test
    @DisplayName("A request stores a PENDING_APPROVAL row for the requester and publishes one audit event")
    void aRequestCreatesAPendingRowAndOneAuditEvent() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.insertRequest(SYSTEM, "orders_in", REQUESTER.toString())).thenReturn(ROW);
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));

        Map<String, Object> view = service.request(SYSTEM, JSON.readTree("{\"table\":\"orders_in\"}"), REQUESTER);

        assertThat(view).containsEntry("id", ROW).containsEntry("table", "orders_in")
                .containsEntry("status", "PENDING_APPROVAL").containsEntry("requestedBy", REQUESTER.toString());
        AuditEvent event = theOneAudit(ConfigAuditActions.DB_TABLE_REQUESTED);
        assertThat(event.who()).isEqualTo(REQUESTER.toString());
        assertThat(event.after()).contains("PENDING_APPROVAL").contains("orders_in");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"table\":\"\"}", "{\"table\":\"orders in\"}", "{\"table\":\"public.orders\"}",
        "{\"table\":\"orders_in; drop table x\"}", "{\"table\":\"1orders\"}", "{\"table\":5}", "{\"table\":null}"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A table that is not a plain identifier is refused 400 before anything is stored")
    void aNonIdentifierTableIsRefused(String body) {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));

        assertThatThrownBy(() -> service.request(SYSTEM, JSON.readTree(body), REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));

        verify(tables, never()).insertRequest(anyString(), anyString(), anyString());
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("Only a DB system takes approved tables: an API system is 400, an unknown or malformed id is 404")
    void onlyADbSystemTakesApprovedTables() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("API")));
        when(systems.findSystem(OTHER_SYSTEM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(SYSTEM, JSON.readTree("{\"table\":\"orders_in\"}"), REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.request(OTHER_SYSTEM, JSON.readTree("{\"table\":\"orders_in\"}"), REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.request("not-a-uuid", JSON.readTree("{\"table\":\"orders_in\"}"), REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.list(OTHER_SYSTEM))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        verify(tables, never()).insertRequest(anyString(), anyString(), anyString());
    }

    @Test
    @Tag("negative")
    @DisplayName("A table already requested or approved for the system is 409, no audit event")
    void aDuplicateRequestIsAConflict() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.insertRequest(anyString(), anyString(), anyString())).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.request(SYSTEM, JSON.readTree("{\"table\":\"orders_in\"}"), REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Another approver approves a pending table: one TABLE_APPROVED audit event naming both people")
    void anotherApproverApproves() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)),
                Optional.of(new ApprovedTableRow(ROW, SYSTEM, "orders_in", "APPROVED", REQUESTER.toString(),
                        Instant.now(), APPROVER.toString(), Instant.now(), true)));
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenReturn("APPROVED");

        Map<String, Object> view = service.decide(SYSTEM, ROW, "APPROVE", APPROVER);

        assertThat(view).containsEntry("status", "APPROVED").containsEntry("decidedBy", APPROVER.toString());
        AuditEvent event = theOneAudit(ConfigAuditActions.DB_TABLE_APPROVED);
        assertThat(event.who()).isEqualTo(APPROVER.toString());
        assertThat(event.before()).contains("PENDING_APPROVAL");
        assertThat(event.after()).contains("APPROVED").contains("orders_in");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("D4: the requester approving their own request is 403 REQUESTER_IS_APPROVER, no audit event")
    void aRequesterNeverApprovesTheirOwnRequest() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(tables.decide(ROW, REQUESTER.toString(), "APPROVE")).thenReturn("REFUSED_SELF_APPROVAL");

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(403);
                    assertThat(e.body()).containsEntry("refusal", "REQUESTER_IS_APPROVER");
                });
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("A decision on a row that is not in the right state is 409; on an unknown row 404")
    void wrongStateOrUnknownRow() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("APPROVED", SYSTEM)));
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenReturn("REFUSED_APPROVED");
        when(tables.decide(ROW, APPROVER.toString(), "REVOKE")).thenReturn("REFUSED_PENDING_APPROVAL");
        String missing = "44444444-4444-4444-4444-444444444444";
        when(tables.find(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body()).containsEntry("refusal", "NOT_PENDING_APPROVAL");
                });
        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "REVOKE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body()).containsEntry("refusal", "NOT_APPROVED");
                });
        assertThatThrownBy(() -> service.decide(SYSTEM, missing, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Karan 1: approving after the system was repointed (host, port, database or user changed) is 409 TARGET_CHANGED")
    void anApprovalOfARepointedTargetIsRefused() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenReturn("REFUSED_TARGET_CHANGED");

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body()).containsEntry("refusal", "TARGET_CHANGED");
                });
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("A row of another system (or one the tenant cannot see) is 404 and the decision is never attempted")
    void aRowOfAnotherSystemIsNotFound() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", OTHER_SYSTEM)));

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        verify(tables, never()).decide(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A revoke of an approved table publishes one TABLE_REVOKED audit event")
    void aRevokePublishesOneAuditEvent() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("APPROVED", SYSTEM)),
                Optional.of(row("REVOKED", SYSTEM)));
        when(tables.decide(ROW, APPROVER.toString(), "REVOKE")).thenReturn("REVOKED");

        assertThat(service.decide(SYSTEM, ROW, "REVOKE", APPROVER)).containsEntry("status", "REVOKED");

        AuditEvent event = theOneAudit(ConfigAuditActions.DB_TABLE_REVOKED);
        assertThat(event.before()).contains("APPROVED");
        assertThat(event.after()).contains("REVOKED");
    }

    @Test
    @DisplayName("The list is the system's rows in the API view")
    void theListIsTheSystemsRows() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.list(SYSTEM)).thenReturn(List.of(row("APPROVED", SYSTEM), row("PENDING_APPROVAL", SYSTEM)));

        assertThat(service.list(SYSTEM)).extracting(m -> m.get("status")).containsExactly("APPROVED",
                "PENDING_APPROVAL");
        verify(tables, never()).insertRequest(any(), any(), any());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("T178a: approving a table on a WIDE account is 409 with the reason; the decision is never attempted")
    void aWideAccountIsRefusedAtApproval() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of("DB_ACCOUNT_TOO_WIDE: SUPERUSER, TRUNCATE"));

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body().toString()).contains("DB_ACCOUNT_TOO_WIDE").contains("SUPERUSER");
                });
        verify(tables, never()).decide(anyString(), anyString(), anyString());
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("T178a: fail closed - a width check that throws refuses the approval (probe unavailable)")
    void aProbeThatCannotRunRefusesTheApproval() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(width.refusalFor(any(), anyString())).thenThrow(new IllegalStateException("probe down"));

        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.body().toString()).contains("DB_ACCOUNT_UNVERIFIED");
                });
        verify(tables, never()).decide(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("T178a: the probe sees the approved table's name; a revoke or a self-approval never dials the partner")
    void theProbeRunsOnlyForARealApproval() {
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)),
                Optional.of(row("APPROVED", SYSTEM)));
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenReturn("APPROVED");
        service.decide(SYSTEM, ROW, "APPROVE", APPROVER);
        org.mockito.ArgumentCaptor<String> settings = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(width).refusalFor(any(), settings.capture());
        assertThat(settings.getValue()).contains("\"table\":\"orders_in\"");

        org.mockito.Mockito.clearInvocations(width);
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(tables.decide(ROW, REQUESTER.toString(), "APPROVE")).thenReturn("REFUSED_SELF_APPROVAL");
        assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", REQUESTER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
        when(tables.find(ROW)).thenReturn(Optional.of(row("APPROVED", SYSTEM)));
        when(tables.decide(ROW, APPROVER.toString(), "REVOKE")).thenReturn("REVOKED");
        service.decide(SYSTEM, ROW, "REVOKE", APPROVER);
        verify(width, never()).refusalFor(any(), anyString());
    }

    /** A transaction manager that does nothing but make {@code isActualTransactionActive()} true inside its scope. */
    private static final class MarkerTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    @Test
    @Tag("security")
    @DisplayName("T178a-1: the partner-database probe runs OUTSIDE any platform transaction; the reads and the decision run inside one")
    void theProbeHoldsNoPlatformTransaction() {
        ApprovedTableService transactional = new ApprovedTableService(tables, systems, events::add,
                () -> Optional.of(TENANT), width, new TransactionTemplate(new MarkerTransactionManager()));
        List<String> trail = new ArrayList<>();
        when(systems.findSystem(SYSTEM)).thenAnswer(call -> {
            trail.add("read tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(system("DB"));
        });
        when(tables.find(ROW)).thenAnswer(call -> {
            trail.add("find tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(row(trail.stream().anyMatch(t -> t.startsWith("decide")) ? "APPROVED" : "PENDING_APPROVAL",
                    SYSTEM));
        });
        when(width.refusalFor(any(), anyString())).thenAnswer(call -> {
            trail.add("probe tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        });
        when(tables.decide(ROW, APPROVER.toString(), "APPROVE")).thenAnswer(call -> {
            trail.add("decide tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return "APPROVED";
        });

        transactional.decide(SYSTEM, ROW, "APPROVE", APPROVER);

        assertThat(trail).as("order is unchanged: system and row, then the probe, then the decision")
                .containsSubsequence("read tx=true", "find tx=true", "probe tx=false", "decide tx=true");
        assertThat(trail).filteredOn(t -> t.startsWith("probe")).containsExactly("probe tx=false");
        assertThat(audits()).hasSize(1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("T178a-1: a refusal still happens before any decision transaction; nothing is decided or audited")
    void aRefusalOpensNoDecisionTransaction() {
        ApprovedTableService transactional = new ApprovedTableService(tables, systems, events::add,
                () -> Optional.of(TENANT), width, new TransactionTemplate(new MarkerTransactionManager()));
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(system("DB")));
        when(tables.find(ROW)).thenReturn(Optional.of(row("PENDING_APPROVAL", SYSTEM)));
        when(width.refusalFor(any(), anyString())).thenReturn(Optional.of("DB_ACCOUNT_TOO_WIDE: SUPERUSER"));

        assertThatThrownBy(() -> transactional.decide(SYSTEM, ROW, "APPROVE", APPROVER))
                .isInstanceOfSatisfying(ConfigApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
        verify(tables, never()).decide(anyString(), anyString(), anyString());
        assertThat(events).isEmpty();
    }
}
