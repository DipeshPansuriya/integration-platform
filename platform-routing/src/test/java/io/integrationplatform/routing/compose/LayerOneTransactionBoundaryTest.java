package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.poolisolation.CountingTransactionManager;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * T71/T175: the layer-1 route wraps NO target type in a whole-call transaction. Every type, DB and FILE included,
 * reads in its own short transaction and holds no pooled connection while the call is in flight. The DB and FILE
 * adapter wiring is pinned by DbPoolIsolationTest and FilePoolIsolationTest; this test pins that the composition
 * hands the registry's calls to the router as they are.
 */
class LayerOneTransactionBoundaryTest {

    private final Stage2Send send =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);

    @Test
    void everyTargetTypeRunsItsCallWithNoTransactionOpen() {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger open = new AtomicInteger(-1);
        TargetCall probe = s -> open.set(pool.open());
        for (String type : new String[] {"API", "MQ", "KAFKA", "DB", "FILE"}) {
            open.set(-1);
            TargetCall routed = PipelineCompositionConfiguration.routedCalls(java.util.Map.of(type, probe)).get(type);
            assertThat(routed).as("the %s call is routed as composed, not wrapped", type).isSameAs(probe);
            routed.call(send);
            assertThat(open.get()).as("transactions open during the %s call", type).isZero();
        }
        assertThat(pool.begun()).isZero();
    }
}
