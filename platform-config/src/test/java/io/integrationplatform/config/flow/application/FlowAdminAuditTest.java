package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.flow.api.InterfacePublished;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12-F2-S1 "One event per action" for the flow lifecycle (row 327 T117, T-068): create, draft-save, submit,
 * approve and publish each publish exactly one {@link AuditEvent} - who, what, when, tenant, the interface and
 * version ids, before/after status or version - and an action that does not complete publishes none.
 */
class FlowAdminAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String V1 = "22222222-2222-2222-2222-222222222221";
    private static final String V2 = "22222222-2222-2222-2222-222222222222";
    private static final String V3 = "22222222-2222-2222-2222-222222222223";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String SNAPSHOT = "{\"interface\":{\"code\":\"F\",\"name\":\"F\",\"ordering\":\"PER_KEY\","
            + "\"businessKeyRule\":\"$.id\"},\"routes\":[],\"mappings\":[]}";

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final SystemAdminService systems = mock(SystemAdminService.class);
    private final List<Object> events = new ArrayList<>();
    private final FlowAdminService service = new FlowAdminService(flows, systems, mock(ContractAdminService.class),
            mock(MappingPublishPinCheck.class), events::add, () -> Optional.of(TENANT));

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    private AuditEvent theOneAudit() {
        assertThat(audits()).as("exactly one audit event for the completed action").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.tenantId()).as("tenant").isEqualTo(TENANT);
        assertThat(event.who()).as("who").isEqualTo(ACTOR.toString());
        assertThat(event.at()).as("when").isNotNull();
        return event;
    }

    private static JsonNode body(String json) {
        return JSON.readTree(json);
    }

    private void aFlowWithLatest(VersionInfo latest) {
        when(flows.findInterface(FLOW)).thenReturn(Optional.of(new InterfaceRow(FLOW, "F", "F", "PER_KEY", "DRAFT")));
        when(flows.findLatestVersion(FLOW)).thenReturn(Optional.of(latest));
        when(flows.findLatestVersionByStatus(FLOW, latest.status())).thenReturn(Optional.of(latest));
    }

    @Test
    void createPublishesOneFlowCreatedEvent() {
        when(systems.systemExists("SRC")).thenReturn(true);
        when(systems.endpointFor("TGT")).thenReturn("ep-1");
        when(flows.insertInterface(anyString(), anyString(), anyString())).thenReturn(FLOW);
        when(flows.insertVersion(any(), anyInt(), any(), any(), any(), any())).thenReturn(V1);

        service.create(body("{\"businessKeyRule\":\"$.id\",\"sourceSystem\":\"SRC\",\"destinations\":[\"TGT\"]}"),
                ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_CREATED);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V1);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"status\":\"DRAFT\",\"version\":1}");
    }

    @Test
    void aRefusedCreatePublishesNothing() {
        when(systems.systemExists("SRC")).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                body("{\"businessKeyRule\":\"$.id\",\"sourceSystem\":\"SRC\",\"destinations\":[\"TGT\"]}"), ACTOR))
                .isInstanceOf(ConfigApiException.class);
        assertThat(audits()).isEmpty();
    }

    @Test
    void saveDraftPublishesOneDraftSavedEventWithTheVersionBeforeAndAfter() {
        aFlowWithLatest(new VersionInfo(V1, 1, "DRAFT", "$.id", SNAPSHOT, null, "SRC"));
        when(systems.systemExists("SRC")).thenReturn(true);
        when(systems.endpointFor("TGT")).thenReturn("ep-1");
        when(flows.insertVersion(any(), anyInt(), any(), any(), any(), any())).thenReturn(V2);

        service.saveDraft(FLOW,
                body("{\"businessKeyRule\":\"$.id\",\"sourceSystem\":\"SRC\",\"destinations\":[\"TGT\"]}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_DRAFT_SAVED);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V2);
        assertThat(event.before()).isEqualTo("{\"version\":1}");
        assertThat(event.after()).isEqualTo("{\"version\":2}");
    }

    @Test
    void submitPublishesOneSubmittedEvent() {
        aFlowWithLatest(new VersionInfo(V2, 2, "DRAFT", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.submitVersion(V2, "ready")).thenReturn(1);

        service.submit(FLOW, body("{\"changeNote\":\"ready\"}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_SUBMITTED);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V2);
        assertThat(event.before()).isEqualTo("{\"status\":\"DRAFT\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
    }

    @Test
    void approvePublishesOneApprovedEvent() {
        aFlowWithLatest(new VersionInfo(V2, 2, "PENDING_APPROVAL", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.approveVersion(V2, ACTOR.toString())).thenReturn("APPROVED");

        service.approve(FLOW, ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_APPROVED);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V2);
        assertThat(event.before()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"APPROVED\"}");
    }

    @Test
    void aRefusedApprovalPublishesNoAuditEvent() {
        aFlowWithLatest(new VersionInfo(V2, 2, "PENDING_APPROVAL", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.approveVersion(V2, ACTOR.toString())).thenReturn("REFUSED_AUTHOR_IS_APPROVER");

        assertThat(service.approve(FLOW, ACTOR).httpStatus()).isEqualTo(403);
        assertThat(audits()).as("the refusal is the database's record (config_version_refusal), not an action")
                .isEmpty();
    }

    @Test
    void publishPublishesOnePublishedEventBesideTheRoutingAnnouncement() {
        aFlowWithLatest(new VersionInfo(V3, 3, "APPROVED", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.publishVersion(V3)).thenReturn(1);

        service.publish(FLOW, ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_PUBLISHED);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V3);
        assertThat(event.before()).isEqualTo("{\"status\":\"APPROVED\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"PUBLISHED\",\"version\":3}");
        assertThat(events).as("the T-116 announcement is unchanged")
                .contains(new InterfacePublished(TENANT, UUID.fromString(FLOW), 3));
    }

    @Test
    @SuppressWarnings("deprecation")
    void aPublishWithoutAnActingUserIsStillAuditedAsTheSystemActor() {
        aFlowWithLatest(new VersionInfo(V3, 3, "APPROVED", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.publishVersion(V3)).thenReturn(1);

        service.publish(FLOW);

        assertThat(audits()).as("no publish path skips the audit").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.FLOW_PUBLISHED);
        assertThat(event.who()).isEqualTo(ConfigAuditActions.SYSTEM_ACTOR.toString());
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "interface_version:" + V3);
    }

    @Test
    void aFailedPublishPublishesNoAuditEvent() {
        aFlowWithLatest(new VersionInfo(V3, 3, "APPROVED", "$.id", SNAPSHOT, null, "SRC"));
        when(flows.publishVersion(V3)).thenReturn(0);

        assertThatThrownBy(() -> service.publish(FLOW, ACTOR)).isInstanceOf(ConfigApiException.class);
        assertThat(audits()).isEmpty();
    }
}
