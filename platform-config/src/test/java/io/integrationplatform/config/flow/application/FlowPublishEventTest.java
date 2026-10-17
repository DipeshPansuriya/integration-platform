package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T-116 (row 278 T68, sanjay-developer): the config publish path tells the running system that an interface
 * version went live - one {@link InterfacePublished} event per successful publish, carrying the tenant, the
 * interface and the published version - so the routing module instantiates the interface's route instances from
 * the configuration rows with 0 deploys and 0 restarts (ADR-010 section 3). A failed publish announces nothing.
 */
class FlowPublishEventTest {

    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String V3 = "22222222-2222-2222-2222-222222222223";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String SNAPSHOT = "{\"interface\":{\"code\":\"F\",\"name\":\"F\",\"ordering\":\"PER_KEY\","
            + "\"businessKeyRule\":\"$.id\"},\"routes\":[],\"mappings\":[]}";

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final FlowAdminService service = new FlowAdminService(flows, mock(SystemAdminService.class),
            mock(ContractAdminService.class), mock(MappingPublishPinCheck.class), events::add,
            () -> Optional.of(TENANT));

    private void anApprovedVersion(int publishedRows) {
        when(flows.findInterface(FLOW)).thenReturn(Optional.of(new InterfaceRow(FLOW, "F", "F", "PER_KEY", "DRAFT")));
        when(flows.findLatestVersionByStatus(FLOW, "APPROVED"))
                .thenReturn(Optional.of(new VersionInfo(V3, 3, "APPROVED", "$.id", SNAPSHOT, null, "SRC")));
        when(flows.publishVersion(V3)).thenReturn(publishedRows);
    }

    @Test
    void aSuccessfulPublishAnnouncesTheInterfaceVersionForRouteInstantiation() {
        anApprovedVersion(1);

        service.publish(FLOW);

        assertThat(events).as("one publish, one announcement the routing module instantiates its routes from")
                .filteredOn(InterfacePublished.class::isInstance) // row327 T117: the publish is also audited
                .containsExactly(new InterfacePublished(TENANT, UUID.fromString(FLOW), 3));
    }

    @Test
    void aFailedPublishAnnouncesNothing() {
        anApprovedVersion(0); // the approved version changed under the publish

        assertThatThrownBy(() -> service.publish(FLOW)).isInstanceOf(ConfigApiException.class);
        assertThat(events).as("no route is instantiated for a publish that did not happen").isEmpty();
    }
}
