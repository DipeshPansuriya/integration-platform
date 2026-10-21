package io.integrationplatform.delivery.internal.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.integrationplatform.delivery.internal.JdbcDeliveryReconcileStore;
import io.integrationplatform.delivery.internal.JdbcDeliveryCircuitFacts;
import io.integrationplatform.delivery.port.TargetHalfOpenCap;
import io.integrationplatform.delivery.port.DeliveryReconcileStore;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * T-180 open item 1 (row 278): the production {@link DeliveryRetryTrigger} is built with the 12-arg constructor -
 * the real {@link JdbcDeliveryReconcileStore} and the application's {@link TargetSendFacts} - and a
 * {@code DELIVERY_RETRY} {@link JobHandler} runs it once per db-scheduler run (ADR-012: no in-JVM timer).
 */
class DeliveryRetryTriggerConfigurationTest {

    private static final TargetSendFacts FACTS =
            id -> Optional.of(new TargetSendFacts.Facts(Duration.ofSeconds(7), true));

    private final AtomicInteger tenantLookups = new AtomicInteger();

    private ApplicationContextRunner runner() {
        ActiveTenantDirectory tenants = () -> {
            tenantLookups.incrementAndGet();
            return List.of();
        };
        return new ApplicationContextRunner()
                .withUserConfiguration(DeliveryRetryTriggerConfiguration.class)
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TenantContext.class, () -> Optional::empty, definition -> definition.setPrimary(true))
                .withBean(ActiveTenantDirectory.class, () -> tenants)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(DeliveryRetryStore.class, () -> mock(DeliveryRetryStore.class))
                .withBean(OutboxWriter.class, () -> mock(OutboxWriter.class));
    }

    @Test
    void wiresTheTriggerWithTheRealReconcileStoreAndTheApplicationsSendFacts() {
        runner().withPropertyValues("IPASS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
                .withBean(TargetSendFacts.class, () -> FACTS)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DeliveryRetryTrigger.class)
                            .hasSingleBean(TriggerTenantContext.class);
                    DeliveryRetryTrigger trigger = context.getBean(DeliveryRetryTrigger.class);
                    assertThat(context.getBean(DeliveryReconcileStore.class))
                            .isInstanceOf(JdbcDeliveryReconcileStore.class);
                    assertThat(trigger).extracting("reconcileStore")
                            .isSameAs(context.getBean(DeliveryReconcileStore.class));
                    assertThat(trigger).extracting("targetFacts").isSameAs(FACTS);
                    assertThat(trigger).extracting("tenantContext")
                            .isSameAs(context.getBean(TriggerTenantContext.class));
                });
    }

    @Test
    void theDeliveryRetryHandlerRunsTheTriggerOncePerRun() {
        runner().withPropertyValues("IPASS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
                .run(context -> {
                    JobHandler handler = context.getBean(JobHandler.class);
                    assertThat(handler.type()).isEqualTo("DELIVERY_RETRY");

                    handler.run(null);

                    assertThat(tenantLookups).hasValue(1);
                });
    }

    @Test
    void withoutAnApplicationSendFactsBeanTheTriggerFallsBackToUnknownFacts() {
        runner().withPropertyValues("IPASS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
                .run(context -> assertThat(context.getBean(DeliveryRetryTrigger.class))
                        .extracting("targetFacts").isNotNull());
    }

    @Test
    void theTriggerReadsTheCircuitFactsFromTheDeliveryLedgerAndCapsAtTheAdrDefaultOfThree() {
        runner().withPropertyValues("IPASS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
                .run(context -> {
                    DeliveryRetryTrigger trigger = context.getBean(DeliveryRetryTrigger.class);
                    assertThat(trigger).extracting("circuitFacts").isInstanceOf(JdbcDeliveryCircuitFacts.class);
                    TargetHalfOpenCap cap = (TargetHalfOpenCap) org.springframework.test.util.ReflectionTestUtils
                            .getField(trigger, "halfOpenCap");
                    assertThat(cap.trialCalls(UUID.randomUUID())).isEqualTo(TargetHalfOpenCap.DEFAULT_TRIAL_CALLS)
                            .isEqualTo(3);
                });
    }

    @Test
    void anApplicationsPerTargetHalfOpenCapReplacesTheDefault() {
        TargetHalfOpenCap five = target -> 5;
        runner().withPropertyValues("IPASS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
                .withBean(TargetHalfOpenCap.class, () -> five)
                .run(context -> assertThat(context.getBean(DeliveryRetryTrigger.class))
                        .extracting("halfOpenCap").isSameAs(five));
    }

    @Test
    void withoutKafkaNoTriggerIsWired() {
        runner().run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(DeliveryRetryTrigger.class).doesNotHaveBean(JobHandler.class));
    }
}
