package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.poolisolation.CountingTransactionManager;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E15/E9 gap: the layer-1 route keeps the whole-call transaction only for the types whose adapters still read the
 * database mid-call (DB, FILE). API (T71), MQ and KAFKA own their short reads and run the call with no transaction.
 */
class LayerOneTransactionBoundaryTest {

    private final Stage2Send send =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);

    private int openDuringCall(String type) {
        CountingTransactionManager pool = new CountingTransactionManager();
        AtomicInteger open = new AtomicInteger(-1);
        TargetCall call = s -> open.set(pool.open());
        PipelineCompositionConfiguration.wrapForType(type, call, new TransactionTemplate(pool)).call(send);
        return open.get();
    }

    @Test
    void apiMqAndKafkaCallsHoldNoTransaction() {
        assertThat(openDuringCall("API")).isZero();
        assertThat(openDuringCall("MQ")).isZero();
        assertThat(openDuringCall("KAFKA")).isZero();
    }

    @Test
    void dbAndFileCallsStillRunInsideOneTransaction() {
        assertThat(openDuringCall("DB")).isEqualTo(1);
        assertThat(openDuringCall("FILE")).isEqualTo(1);
    }
}
