package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.internal.persistence.JdbcFlowRouteNames;
import io.integrationplatform.config.internal.persistence.JdbcFlowRouteNames.EndpointRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T81 / B9 (row 278 wave 2): the routes-listing read model the flow designer navigates by. It lists the routes
 * of the flow's current (latest, i.e. draft or newest) version - or of a version named by id - straight from that
 * version's {@link FlowBundle} snapshot, each named by its target endpoint, with the mapping the designer opens.
 */
class FlowRoutesQueryTest {

    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String V1 = "22222222-2222-2222-2222-222222222221";
    private static final String V2 = "22222222-2222-2222-2222-222222222222";
    private static final String ROUTE_A = "33333333-3333-3333-3333-33333333333a";
    private static final String ROUTE_B = "33333333-3333-3333-3333-33333333333b";
    private static final String MAPPING_A = "44444444-4444-4444-4444-44444444444a";
    private static final String MAPPING_B = "44444444-4444-4444-4444-44444444444b";
    private static final String EP_A = "55555555-5555-5555-5555-55555555555a";
    private static final String EP_B = "55555555-5555-5555-5555-55555555555b";

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final JdbcFlowRouteNames names = mock(JdbcFlowRouteNames.class);
    private final FlowRoutesQuery query = new FlowRoutesQuery(flows, names);

    private static String snapshot(String... routeIdEndpointMapping) {
        StringBuilder routes = new StringBuilder();
        StringBuilder mappings = new StringBuilder();
        for (int i = 0; i < routeIdEndpointMapping.length; i += 3) {
            int sequence = i / 3 + 1;
            String sep = i == 0 ? "" : ",";
            routes.append(sep).append("{\"id\":\"").append(routeIdEndpointMapping[i]).append("\",\"sequence\":")
                    .append(sequence).append(",\"condition\":null,\"isPrimary\":").append(sequence == 1)
                    .append(",\"status\":\"ACTIVE\",\"targetEndpointId\":\"").append(routeIdEndpointMapping[i + 1])
                    .append("\"}");
            mappings.append(sep).append("{\"id\":\"").append(routeIdEndpointMapping[i + 2])
                    .append("\",\"routeId\":\"").append(routeIdEndpointMapping[i])
                    .append("\",\"fieldMappings\":[],\"compiledExpression\":\"$\"}");
        }
        return "{\"interface\":{\"code\":\"F\",\"name\":\"F\",\"ordering\":\"PER_KEY\",\"businessKeyRule\":\"$.id\"},"
                + "\"routes\":[" + routes + "],\"mappings\":[" + mappings + "]}";
    }

    private static VersionInfo version(String id, int number, String status, String snapshot) {
        return new VersionInfo(id, number, status, "$.id", snapshot, null, "SRC");
    }

    private void givenFlow() {
        when(flows.findInterface(FLOW)).thenReturn(Optional.of(
                new InterfaceRow(FLOW, "F", "Flow F", "PER_KEY", "DRAFT")));
        when(names.findEndpoints(any())).thenReturn(Map.of(
                EP_A, new EndpointRow(EP_A, "EP-A", "Endpoint A", "TGT-A"),
                EP_B, new EndpointRow(EP_B, "EP-B", "Endpoint B", "TGT-B")));
    }

    @Test
    @DisplayName("lists the current version's routes in sequence order, each named by its target endpoint")
    @SuppressWarnings("unchecked")
    void listsTheCurrentVersionsRoutes() {
        givenFlow();
        when(flows.findLatestVersion(FLOW)).thenReturn(Optional.of(
                version(V2, 2, "DRAFT", snapshot(ROUTE_A, EP_A, MAPPING_A, ROUTE_B, EP_B, MAPPING_B))));

        Map<String, Object> answer = query.routes(FLOW, null);

        assertThat(answer).containsEntry("flowId", FLOW).containsEntry("versionId", V2)
                .containsEntry("version", 2).containsEntry("versionStatus", "DRAFT");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) answer.get("routes");
        assertThat(routes).extracting(r -> r.get("id")).containsExactly(ROUTE_A, ROUTE_B);
        assertThat(routes.get(0))
                .containsEntry("name", "Endpoint A")
                .containsEntry("sequence", 1)
                .containsEntry("isPrimary", true)
                .containsEntry("status", "ACTIVE")
                .containsEntry("condition", null)
                .containsEntry("targetEndpointId", EP_A)
                .containsEntry("targetEndpointCode", "EP-A")
                .containsEntry("targetSystem", "TGT-A")
                .containsEntry("mappingId", MAPPING_A);
        assertThat(routes.get(1)).containsEntry("name", "Endpoint B").containsEntry("mappingId", MAPPING_B)
                .containsEntry("isPrimary", false);
    }

    @Test
    @DisplayName("lists the routes of a version named by id")
    @SuppressWarnings("unchecked")
    void listsTheRoutesOfANamedVersion() {
        givenFlow();
        when(flows.findVersionOfInterface(FLOW, V1)).thenReturn(Optional.of(
                version(V1, 1, "PUBLISHED", snapshot(ROUTE_A, EP_A, MAPPING_A))));

        Map<String, Object> answer = query.routes(FLOW, V1);

        assertThat(answer).containsEntry("versionId", V1).containsEntry("versionStatus", "PUBLISHED");
        assertThat((List<Map<String, Object>>) answer.get("routes")).extracting(r -> r.get("id"))
                .containsExactly(ROUTE_A);
    }

    @Test
    @DisplayName("an unknown or other-tenant flow answers 404")
    void anUnknownFlowIsNotFound() {
        when(flows.findInterface(FLOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.routes(FLOW, null)).isInstanceOf(ConfigApiException.class)
                .extracting(e -> ((ConfigApiException) e).httpStatus()).isEqualTo(404);
        verifyNoInteractions(names);
    }

    @Test
    @DisplayName("a version that is not the flow's answers 404")
    void anUnknownVersionIsNotFound() {
        givenFlow();
        when(flows.findVersionOfInterface(FLOW, V1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.routes(FLOW, V1)).isInstanceOf(ConfigApiException.class)
                .extracting(e -> ((ConfigApiException) e).httpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a malformed id names no visible row: 404 without touching the database")
    void aMalformedIdIsNotFound() {
        assertThatThrownBy(() -> query.routes("not-a-uuid", null)).isInstanceOf(ConfigApiException.class)
                .extracting(e -> ((ConfigApiException) e).httpStatus()).isEqualTo(404);
        assertThatThrownBy(() -> query.routes(FLOW, "nope")).isInstanceOf(ConfigApiException.class)
                .extracting(e -> ((ConfigApiException) e).httpStatus()).isEqualTo(404);
        verifyNoInteractions(flows, names);
    }
}
