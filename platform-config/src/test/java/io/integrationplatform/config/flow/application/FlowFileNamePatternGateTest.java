package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.DestinationRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T178c-1: a flow whose FILE destination still carries a stored fixed file name (saved before T178c) is neither
 * approved nor published - a static 409 until an operator edits the pattern. Nothing is rewritten.
 */
class FlowFileNamePatternGateTest {

    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String V3 = "22222222-2222-2222-2222-222222222223";
    private static final UUID APPROVER = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String SNAPSHOT = "{\"interface\":{\"code\":\"F\",\"name\":\"F\",\"ordering\":\"PER_KEY\","
            + "\"businessKeyRule\":\"$.id\"},\"routes\":[],\"mappings\":[]}";

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final SystemAdminService systems = mock(SystemAdminService.class);
    private final FlowAdminService service = new FlowAdminService(flows, systems, mock(ContractAdminService.class),
            mock(MappingPublishPinCheck.class), event -> { }, () -> Optional.of(UUID.randomUUID()));

    private void aFlowWithFileDestination(long withoutDeliveryId) {
        when(flows.findInterface(FLOW)).thenReturn(Optional.of(new InterfaceRow(FLOW, "F", "F", "PER_KEY", "DRAFT")));
        when(flows.findDestinations(FLOW)).thenReturn(List.of(new DestinationRow(1, "files")));
        when(systems.countFileDestinationsWithoutDeliveryId(List.of("files"))).thenReturn(withoutDeliveryId);
        VersionInfo version = new VersionInfo(V3, 3, "PENDING_APPROVAL", "$.id", SNAPSHOT, null, "SRC");
        when(flows.findLatestVersionByStatus(FLOW, "PENDING_APPROVAL")).thenReturn(Optional.of(version));
        when(flows.findLatestVersionByStatus(FLOW, "APPROVED"))
                .thenReturn(Optional.of(new VersionInfo(V3, 3, "APPROVED", "$.id", SNAPSHOT, null, "SRC")));
        when(flows.approveVersion(anyString(), anyString())).thenReturn("APPROVED");
        when(flows.publishVersion(V3)).thenReturn(1);
    }

    @Test
    void approveIsRefusedWithAStaticCodeAndTheVersionIsNotApproved() {
        aFlowWithFileDestination(1);

        FlowAdminService.ApprovalOutcome outcome = service.approve(FLOW, APPROVER);

        assertThat(outcome.httpStatus()).isEqualTo(409);
        assertThat(outcome.body()).containsEntry("refusal", "FILE_NAME_PATTERN_NEEDS_DELIVERY_ID");
        verify(flows, never()).approveVersion(any(), any());
    }

    @Test
    void publishIsRefusedToo() {
        aFlowWithFileDestination(1);

        assertThatThrownBy(() -> service.publish(FLOW)).isInstanceOfSatisfying(ConfigApiException.class,
                e -> assertThat(e.body()).containsEntry("refusal", "FILE_NAME_PATTERN_NEEDS_DELIVERY_ID"));
        verify(flows, never()).publishVersion(any());
    }

    @Test
    void aFlowWithoutSuchADestinationApprovesAsBefore() {
        aFlowWithFileDestination(0);

        assertThat(service.approve(FLOW, APPROVER).httpStatus()).isEqualTo(200);
    }
}
