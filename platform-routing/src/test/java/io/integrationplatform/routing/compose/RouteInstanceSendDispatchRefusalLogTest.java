package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Row 278 B22, Kavita M1 (CWE-532/117): the stage 2 WARN line for a refused route instance names the scheme, the
 * target endpoint id and the route id - never the endpoint URI (userinfo, a query token or CR/LF would reach the log).
 */
@Tag("e9-t116")
@ExtendWith(OutputCaptureExtension.class)
class RouteInstanceSendDispatchRefusalLogTest {

    private static final String DIRTY_URI = "exec://user:pw@host/bin/echo?token=s3cret\r\nX-Injected: yes";
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void theRefusedRouteWarnLineNeverCarriesTheEndpointUri(CapturedOutput output) throws Exception {
        UUID route = seed("route-dirty");
        UUID endpoint = seed("endpoint-dirty");
        UUID delivery = seed("delivery-dirty");
        RouteConfigRow row = new RouteConfigRow(route, endpoint, "API", DIRTY_URI, null, null);
        DefaultCamelContext camel = new DefaultCamelContext();
        camel.start();
        try {
            RouteInstanceFactory routes = new RouteInstanceFactory((t, i) -> List.of(row), camel, List.of(),
                    Set.of("https"), Duration.ofMinutes(30), () -> NOW);
            UUID tenant = seed("tenant");
            JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
            when(deliveries.claimed(delivery)).thenReturn(Optional.of(
                    new JdbcDueDeliveries.DueDelivery(delivery, route, endpoint, seed("iface"), "key-1")));
            TenantContext tenants = mock(TenantContext.class);
            when(tenants.requireTenantId()).thenReturn(tenant);
            RouteInstanceSendDispatch stage2 = new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(),
                    deliveries, tenants, (t, i) -> List.of(row), routes, mock(ProducerTemplate.class),
                    mock(SendOutcomes.class), () -> NOW);

            stage2.dispatch(new Stage2Send(delivery, UUID.randomUUID(), "key-1", endpoint, false));
        } finally {
            camel.stop();
        }

        String all = output.getAll();
        int at = all.indexOf("stage 2: delivery " + delivery + " - its route instance was refused");
        assertThat(at).as("the WARN line was logged").isNotNegative();
        String fromTheLine = all.substring(at);
        String theLine = fromTheLine.lines().findFirst().orElseThrow();
        assertThat(theLine).as("one line naming the scheme, the endpoint id and the route id")
                .contains("'exec'", endpoint.toString(), route.toString());
        assertThat(fromTheLine).doesNotContain("user:pw", "s3cret", "token=", "X-Injected");
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
