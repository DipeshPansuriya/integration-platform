package io.integrationplatform.routing.compose;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.routing.templates.RouteTemplate;
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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T161 N-2: a route-id clash (the route is in the Camel context but this instance holds none) is a
 * RouteBuildRefusedException, and stage 2 records it as a PERMANENT failure (dead-letter), never a transient one that
 * redelivers: the same row would clash again until the orphan route is gone. The L2 fix stops an idle sweep from
 * creating that orphan; this pins how a clash that still happens is classified.
 */
class RouteInstanceSendDispatchClashTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRouteIdClashIsRecordedPermanentNotTransient() throws Exception {
        UUID route = seed("route");
        UUID endpoint = seed("endpoint");
        UUID delivery = seed("delivery");
        UUID claim = UUID.randomUUID();
        RouteConfigRow row = new RouteConfigRow(route, endpoint, "API", "https://target.example/v1", null, null);
        RouteTemplate template = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow each) {
                return exchange -> { };
            }
        };
        DefaultCamelContext camel = new DefaultCamelContext();
        camel.start();
        try {
            new RouteInstanceFactory((t, i) -> List.of(row), camel, List.of(template), Set.of("https"),
                    Duration.ofMinutes(30), () -> NOW).ensure(java.util.UUID.fromString("00000000-0000-4000-8000-0000000000aa"), row); // the route is now in the context
            RouteInstanceFactory clashing = new RouteInstanceFactory((t, i) -> List.of(row), camel,
                    List.of(template), Set.of("https"), Duration.ofMinutes(30), () -> NOW);
            JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
            when(deliveries.claimed(delivery)).thenReturn(Optional.of(
                    new JdbcDueDeliveries.DueDelivery(delivery, route, endpoint, seed("iface"), "key-1")));
            TenantContext tenants = mock(TenantContext.class);
            when(tenants.requireTenantId()).thenReturn(seed("tenant"));
            SendOutcomes outcomes = mock(SendOutcomes.class);
            RouteInstanceSendDispatch stage2 = new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(),
                    deliveries, tenants, (t, i) -> List.of(row), clashing, mock(ProducerTemplate.class), outcomes,
                    () -> NOW);

            stage2.dispatch(new Stage2Send(delivery, claim, "key-1", endpoint, false));

            verify(outcomes).recordFailure(eq(delivery), eq(claim), anyInt(), eq(OutcomeClass.PERMANENT),
                    anyBoolean());
            verify(outcomes, never()).recordFailure(eq(delivery), eq(claim), anyInt(), eq(OutcomeClass.TRANSIENT),
                    anyBoolean());
        } finally {
            camel.stop();
        }
    }
}
