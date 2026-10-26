package io.integrationplatform.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E13/E18 gap (lane tracking-stages): the running pipeline must hand the application's {@link StageTimings} to the
 * stage-1 guard (ROUTE) and to the send outcomes (DELIVER, on both the success move and the recorder's dead letter).
 * The T105 writers existed, but this composition built them through the no-timing constructors, so the stages never
 * wrote. A bare slice without the timing bean still builds.
 */
class DeliveryPipelineConfigurationTest {

    private final StageTimings timings = mock(StageTimings.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DeliveryPipelineConfiguration.class)
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TenantContext.class, () -> () -> Optional.empty());
    }

    @Test
    void stageOneGetsTheStageTimingsSoTheGuardFlipWritesRoute() {
        runner().withBean(StageTimings.class, () -> timings).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Stage1Deliveries.class)).hasFieldOrPropertyWithValue("timings", timings);
        });
    }

    @Test
    void theSendOutcomesGetTheStageTimingsForDeliveredAndForTheDeadLetter() {
        runner().withBean(StageTimings.class, () -> timings).run(context -> {
            assertThat(context).hasNotFailed();
            SendOutcomes outcomes = context.getBean(SendOutcomes.class);
            assertThat(outcomes).hasFieldOrPropertyWithValue("timings", timings);
            assertThat(outcomes).extracting("recorder").hasFieldOrPropertyWithValue("timings", timings);
        });
    }

    @Test
    void aBareSliceWithoutTheTimingBeanStillBuilds() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Stage1Deliveries.class)).hasFieldOrPropertyWithValue("timings", null);
            assertThat(context.getBean(SendOutcomes.class)).hasFieldOrPropertyWithValue("timings", null);
        });
    }
}
