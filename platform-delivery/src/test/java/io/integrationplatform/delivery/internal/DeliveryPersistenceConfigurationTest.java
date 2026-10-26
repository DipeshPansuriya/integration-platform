package io.integrationplatform.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lane deliver-deadletter: the retry store bean (used by the retry trigger and the credentialed send) gets the
 * application's {@link StageTimings} through {@code ObjectProvider.getIfAvailable}, the same pattern as
 * {@link DeliveryPipelineConfiguration}, so a dead letter through it writes the DELIVER stage; a bare slice
 * without the timing bean still builds and the store simply writes none.
 */
class DeliveryPersistenceConfigurationTest {

    private final StageTimings timings = mock(StageTimings.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DeliveryPersistenceConfiguration.class)
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TenantContext.class, () -> () -> Optional.empty());
    }

    @Test
    void theRetryStoreGetsTheStageTimingsSoItsDeadLettersWriteDeliver() {
        runner().withBean(StageTimings.class, () -> timings).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeliveryRetryStore.class)).hasFieldOrPropertyWithValue("timings", timings);
        });
    }

    @Test
    void aBareSliceWithoutTheTimingBeanStillBuilds() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeliveryRetryStore.class)).hasFieldOrPropertyWithValue("timings", null);
        });
    }
}
