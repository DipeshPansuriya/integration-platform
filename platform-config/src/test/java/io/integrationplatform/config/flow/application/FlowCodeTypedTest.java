package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B26 (row 323, parent F21): the flow create honors the typed interface code - the code the base suite
 * (release-suite-base-20261022 §2, BS-06) and the common intake POST depend on ("flow 'orders'" reached as
 * /api/v1/interfaces/orders/messages). When the body carries a non-blank {@code code} it IS the interface code
 * (V2_2_3: per-tenant unique, so a clash stays the database's unique constraint - 409 at the adapter); with no
 * code named, the mint (today's shape) keeps every existing caller working.
 */
class FlowCodeTypedTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String INTERFACE_ID = "11111111-1111-1111-1111-111111111111";

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final SystemAdminService systems = mock(SystemAdminService.class);
    private final FlowAdminService service = new FlowAdminService(flows, systems, mock(ContractAdminService.class),
            mock(MappingPublishPinCheck.class), event -> {
            }, () -> Optional.of(UUID.fromString("99999999-9999-9999-9999-999999999999")));

    private JsonNode body(String json) {
        return JSON.readTree(json);
    }

    @Test
    void createGivesTheInterfaceTheTypedCode() {
        when(systems.systemExists("SRC")).thenReturn(true);
        when(systems.endpointFor("TGT")).thenReturn("ep-1");
        when(flows.insertInterface(anyString(), anyString(), anyString())).thenReturn(INTERFACE_ID);
        when(flows.insertVersion(any(), anyInt(), any(), any(), any(), any())).thenReturn("v");

        InterfaceRow created = service.create(
                body("{\"code\":\"orders\",\"name\":\"orders\",\"businessKeyRule\":\"orderId\","
                        + "\"sourceSystem\":\"SRC\",\"destinations\":[\"TGT\"]}"), ACTOR);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(flows).insertInterface(code.capture(), anyString(), anyString());
        assertThat(code.getValue()).as("the typed code is the interface code").isEqualTo("orders");
        assertThat(created.code()).as("the typed code answers back").isEqualTo("orders");
    }

    @Test
    void createStillMintsTheCodeWhenNoneIsNamed() {
        when(systems.systemExists("SRC")).thenReturn(true);
        when(systems.endpointFor("TGT")).thenReturn("ep-1");
        when(flows.insertInterface(anyString(), anyString(), anyString())).thenReturn(INTERFACE_ID);
        when(flows.insertVersion(any(), anyInt(), any(), any(), any(), any())).thenReturn("v");

        InterfaceRow created = service.create(
                body("{\"businessKeyRule\":\"$.id\",\"sourceSystem\":\"SRC\",\"destinations\":[\"TGT\"]}"), ACTOR);

        assertThat(created.code()).startsWith("flow-");
    }
}