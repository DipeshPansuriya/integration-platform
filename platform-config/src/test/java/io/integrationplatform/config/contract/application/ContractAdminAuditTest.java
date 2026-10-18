package io.integrationplatform.config.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionDetail;
import io.integrationplatform.config.internal.persistence.JdbcContractRepository.VersionRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12-F2-S1 "One event per action" for contract versions (row 327 T117, T-134): an import and an approval each
 * publish exactly one {@link AuditEvent} - who, what, when, tenant, the contract version id, status before/after -
 * never the schema or the document; a refused approval publishes none (it rolls back, and the refusal stays the
 * T-105 log line).
 */
class ContractAdminAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = "88888888-8888-8888-8888-888888888888";
    private static final String CONTRACT = "12121212-1212-1212-1212-121212121212";
    private static final String VERSION = "13131313-1313-1313-1313-131313131313";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final JdbcContractRepository contracts = mock(JdbcContractRepository.class);
    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final ContractAdminService service = new ContractAdminService(contracts, systems, events::add,
            () -> Optional.of(TENANT));

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    private static VersionDetail version(String status) {
        return new VersionDetail(VERSION, CONTRACT, "tgt-a", "TARGET_REQUEST", "1.0", status,
                "{\"type\":\"object\",\"properties\":{\"secretField\":{\"type\":\"string\"}}}", null, null, null,
                ACTOR.toString(), null, Instant.now());
    }

    private AuditEvent theOneAudit() {
        assertThat(audits()).as("exactly one audit event for the completed action").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.at()).isNotNull();
        assertThat(event.targetIds()).containsExactly("contract_version:" + VERSION);
        assertThat(String.valueOf(event.before()) + event.after()).as("no schema in the audit")
                .doesNotContain("secretField");
        return event;
    }

    @Test
    void anImportPublishesOneContractVersionImportedEvent() {
        when(systems.findSystemByCode("tgt-a")).thenReturn(Optional.of(new SystemRow(SYSTEM, "tgt-a", "A", "OUTBOUND")));
        when(contracts.findContract(SYSTEM, "TARGET_REQUEST")).thenReturn(Optional.empty());
        when(contracts.insertContract(any(), any(), any(), any())).thenReturn(CONTRACT);
        when(contracts.insertImportedVersion(any(), any(), any(), any(), any(), any())).thenReturn(VERSION);
        when(contracts.findVersionDetail(VERSION)).thenReturn(Optional.of(version("DRAFT")));

        service.importVersion(JSON.readTree("{\"system\":\"tgt-a\",\"role\":\"TARGET_REQUEST\",\"versionLabel\":\"1.0\","
                + "\"format\":\"JSON_SCHEMA\",\"document\":{\"type\":\"object\",\"properties\":"
                + "{\"secretField\":{\"type\":\"string\"}}}}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.CONTRACT_VERSION_IMPORTED);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"status\":\"DRAFT\",\"versionLabel\":\"1.0\"}");
    }

    @Test
    void anApprovalPublishesOneContractVersionApprovedEvent() {
        when(contracts.approveVersion(VERSION, ACTOR.toString())).thenReturn("APPROVED");
        when(contracts.findVersionDetail(VERSION)).thenReturn(Optional.of(version("APPROVED")));

        service.approve(VERSION, ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.CONTRACT_VERSION_APPROVED);
        assertThat(event.before()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"APPROVED\",\"versionLabel\":\"1.0\"}");
    }

    @Test
    void aDraftSavePublishesOneContractDraftSavedEvent() {
        when(systems.findSystemByCode("src-a")).thenReturn(Optional.of(new SystemRow(SYSTEM, "src-a", "S", "INBOUND")));
        when(contracts.findContractByCode("src-a")).thenReturn(Optional.empty());
        when(contracts.insertContract(any(), any(), any())).thenReturn(CONTRACT);
        when(contracts.insertVersion(any(), any(), any(), any())).thenReturn(VERSION);
        when(contracts.findVersionById(VERSION)).thenReturn(Optional.of(new VersionRow(VERSION, CONTRACT, "1.0",
                "DRAFT", "{\"type\":\"object\",\"properties\":{\"secretField\":{\"type\":\"string\"}}}", null,
                "src-a")));

        service.saveDraft(JSON.readTree("{\"system\":\"src-a\",\"role\":\"SOURCE_REQUEST\",\"name\":\"S\","
                + "\"sourceSchema\":{\"type\":\"object\",\"properties\":{\"secretField\":{\"type\":\"string\"}}}}"),
                ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.CONTRACT_DRAFT_SAVED);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"status\":\"DRAFT\",\"versionLabel\":\"1.0\"}");
    }

    @Test
    void aSubmitPublishesOneContractSubmittedEvent() {
        when(contracts.submitVersion(VERSION, "ready")).thenReturn("SUBMITTED");

        service.submit(VERSION, JSON.readTree("{\"changeNote\":\"ready\"}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.CONTRACT_SUBMITTED);
        assertThat(event.before()).isEqualTo("{\"status\":\"DRAFT\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
    }

    @Test
    void aRefusedSubmitPublishesNothing() {
        when(contracts.submitVersion(VERSION, "ready")).thenReturn("NOT_DRAFT");

        assertThatThrownBy(() -> service.submit(VERSION, JSON.readTree("{\"changeNote\":\"ready\"}"), ACTOR))
                .isInstanceOf(ConfigApiException.class);
        assertThat(audits()).isEmpty();
    }

    @Test
    void aRefusedApprovalPublishesNothing() {
        when(contracts.approveVersion(VERSION, ACTOR.toString())).thenReturn("REFUSED_AUTHOR_IS_APPROVER");

        assertThatThrownBy(() -> service.approve(VERSION, ACTOR)).isInstanceOf(ConfigApiException.class);
        assertThat(audits()).isEmpty();
    }
}
