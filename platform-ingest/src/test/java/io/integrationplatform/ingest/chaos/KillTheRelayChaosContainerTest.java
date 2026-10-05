package io.integrationplatform.ingest.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.chaos.OutboxChaosDatabase.OutboxRow;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxRelay;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.ingest.port.OutboundRecord;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-009 (E2-F1-S1, lanes A+B integrated by arjun-fullstack): kill-the-relay chaos against PostgreSQL in
 * Testcontainers - the proof of RPO zero for accepted messages (E2-C1, E2-C8) and of post-crash duplicate
 * detection (E2-C2). Tagged {@code container} (R03): runs only under {@code ./mvnw -Pcontainer}, never in the
 * bare default suite.
 *
 * <p>State today (written test-first): lanes A (T-007 writer) and B (T-008 relay) are not integrated yet, so the
 * Spring lookup in {@link #requireIntegrated} fails each test with a named "pending integration" message - that
 * is the committed RED. The suite compiles against the frozen seam only. Nothing here is stubbed to force green:
 * the writer, the relay and the tenant stamping are the real lane classes the moment they exist as beans.
 *
 * <p>The crash is simulated in-process (the brief's decorator shape): a {@link FaultInjectingPublishPort} that
 * dies at a chosen seam point, then the cycle is re-driven - "rebuild the relay" is satisfied by re-running
 * {@code runOnce()} against the same bean, which is itself part of what the suite proves: the relay contract
 * promises every unmarked row lives in the database, not in memory, so a singleton re-drive and a fresh process
 * must behave identically.
 */
@Tag("container")
@SpringBootTest(
        classes = OutboxChaosTestApplication.class,
        properties = {"spring.flyway.enabled=false", "spring.main.web-application-type=none"})
class KillTheRelayChaosContainerTest {

    /** The scenario's "1,000 messages" - kept at the stated size (E2-C1 names the figure). */
    private static final int RPO_SEED_COUNT = 1_000;

    /** Guard against an endless re-publish loop: no cycle over this many means a broken relay, not slow CI. */
    private static final int MAX_DRAIN_CYCLES = 200;

    /** One row as lane A's writer committed it - the suite's own ledger for the tie-out assertions. */
    private record Seeded(long outboxId, UUID tenantId, UUID aggregateId, String recordKey) {
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChaosTenantContext tenantContext;

    @Autowired
    private FaultInjectingPublishPort faultPort;

    @Autowired
    private TransactionTemplate transaction;

    private final SimulatedConsumer consumer = new SimulatedConsumer();

    @BeforeEach
    void cleanSlate() throws SQLException {
        OutboxChaosDatabase.get(); // the fixture starts on first use (also when the DataSource bean was made)
        OutboxChaosDatabase.get().truncateOutbox();
        faultPort.reset();
        consumer.reset();
        tenantContext.clear();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("S1: kill the relay between commit and publish - all 1,000 are published, 0 lost (RPO zero, E2-C1/E2-C8)")
    void killBetweenCommitAndPublishLosesNothing() throws SQLException {
        OutboxWriter writer = requireIntegrated(OutboxWriter.class, "lane A", "T-007");
        OutboxRelay relay = requireIntegrated(OutboxRelay.class, "lane B", "T-008");

        List<Seeded> seeded = seed(OutboxChaosDatabase.TENANT_A, RPO_SEED_COUNT, writer);
        assertThat(seeded).as("the 1,000 message and outbox rows are committed before the kill").hasSize(RPO_SEED_COUNT);
        assertThat(OutboxChaosDatabase.get().countUnpublished()).isEqualTo(RPO_SEED_COUNT);

        // When: killed after the commit and before the publish.
        faultPort.armCrash(FaultInjectingPublishPort.CrashPoint.BEFORE_PUBLISH);
        assertThatThrownBy(relay::runOnce)
                .as("a relay that swallows even an Error at the publish seam hides the crash")
                .isInstanceOf(FaultInjectingPublishPort.SimulatedCrash.class);
        assertThat(faultPort.brokerLog()).as("the kill was before any publish - nothing reached the broker").isEmpty();
        assertThat(OutboxChaosDatabase.get().countUnpublished())
                .as("nothing was marked by a cycle that died")
                .isEqualTo(RPO_SEED_COUNT);

        // ...then restarted, and finish the cycle.
        faultPort.clearFault();
        int marked = drain(relay);

        // Then: RPO zero - every committed row published, every idempotency key on the broker exactly once.
        assertThat(OutboxChaosDatabase.get().countUnpublished()).as("0 lost (RPO zero)").isZero();
        assertThat(marked).as("sum of rows marked published over the restart cycles is exactly the seed count")
                .isEqualTo(RPO_SEED_COUNT);
        List<OutboundRecord> broker = faultPort.brokerLog();
        assertThat(broker).extracting(OutboundRecord::outboxId)
                .containsExactlyInAnyOrderElementsOf(seeded.stream().map(Seeded::outboxId).toList());
        feedConsumer(broker);
        assertThat(consumer.duplicateKeys()).as("this scenario kills before publish - no duplicate expected")
                .isZero();
        assertThat(consumer.distinctKeys()).isEqualTo(RPO_SEED_COUNT);
    }

    @Test
    @Tag("negative")
    @DisplayName("S2 @negative: published-then-crashed row is re-published and the duplicate is detected (E2-C2)")
    void postCrashDuplicateIsDetectedByTheIdempotencyKey() throws SQLException {
        OutboxWriter writer = requireIntegrated(OutboxWriter.class, "lane A", "T-007");
        OutboxRelay relay = requireIntegrated(OutboxRelay.class, "lane B", "T-008");

        List<Seeded> seeded = seed(OutboxChaosDatabase.TENANT_A, 5, writer);

        // Given: the relay published a message and crashed before marking it sent.
        faultPort.armCrash(FaultInjectingPublishPort.CrashPoint.AFTER_PUBLISH_BEFORE_ACK);
        assertThatThrownBy(relay::runOnce)
                .as("the crash at the worst seam point must reach the driver")
                .isInstanceOf(FaultInjectingPublishPort.SimulatedCrash.class);
        List<OutboundRecord> beforeRestart = faultPort.brokerLog();
        assertThat(beforeRestart).as("exactly one record reached the broker before the kill").hasSize(1);
        assertThat(OutboxChaosDatabase.get().countUnpublished())
                .as("the crash left the published row unmarked (nothing marked at all)")
                .isEqualTo(5);

        // When: the restarted relay publishes it again.
        faultPort.clearFault();
        int marked = drain(relay);

        // Then: the receiving side detects the duplicate by its idempotency key and does not deliver twice.
        assertThat(OutboxChaosDatabase.get().countUnpublished()).isZero();
        assertThat(marked).as("each of the 5 rows is marked exactly once, despite the re-publish").isEqualTo(5);
        List<OutboundRecord> broker = faultPort.brokerLog();
        OutboundRecord crashed = beforeRestart.get(0);
        assertThat(broker).as("the crashed row was seen by the broker twice, the other four once")
                .filteredOn(r -> r.outboxId() == crashed.outboxId())
                .hasSize(2);
        feedConsumer(broker);
        assertThat(consumer.distinctKeys()).as("all 5 rows delivered").isEqualTo(5);
        assertThat(consumer.duplicateKeys())
                .as("the one re-published row is detected, not silently delivered a second time")
                .isEqualTo(1);
        assertThat(crashed.idempotencyKey()).isEqualTo("outbox:" + crashed.outboxId());
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("S3 @negative @tenant: every published record is tied to its own outbox row of its own tenant")
    void relayNeverMixesTenants() throws SQLException {
        OutboxWriter writer = requireIntegrated(OutboxWriter.class, "lane A", "T-007");
        OutboxRelay relay = requireIntegrated(OutboxRelay.class, "lane B", "T-008");

        // Given: outbox rows of tenant A and tenant B, interleaved in id order within one drain.
        int perTenant = 50;
        Map<Long, Seeded> byId = new LinkedHashMap<>();
        for (int i = 0; i < perTenant; i++) {
            for (UUID tenant : List.of(OutboxChaosDatabase.TENANT_A, OutboxChaosDatabase.TENANT_B)) {
                seed(tenant, 1, i, writer).forEach(s -> byId.put(s.outboxId(), s));
            }
        }
        assertThat(byId).hasSize(2 * perTenant);

        // When: the relay claims and publishes them.
        int marked = drain(relay);

        // Then: each published record is tied to its own row (and no row is read outside its tenant policy -
        // a mix would show as a record whose stored row belongs to the other tenant).
        assertThat(marked).as("all rows of both tenants drain in the suite cycles").isEqualTo(2 * perTenant);
        List<OutboundRecord> broker = faultPort.brokerLog();
        assertThat(broker).as("one record per committed row").hasSize(2 * perTenant);
        for (OutboundRecord record : broker) {
            Seeded mine = byId.get(record.outboxId());
            assertThat(mine).as("record names a row this suite committed; the relay invented or crossed an id")
                    .isNotNull();
            OutboxRow stored;
            try {
                stored = OutboxChaosDatabase.get().readOutboxRow(record.outboxId());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            assertThat(stored.tenantId()).as("row %d keeps its own tenant", record.outboxId()).isEqualTo(mine.tenantId());
            assertThat(record.aggregateId()).as("record carries its row's message id").isEqualTo(mine.aggregateId());
            assertThat(record.key()).as("record carries its row's key").isEqualTo(mine.recordKey());
            assertThat(record.topic()).isEqualTo(OutboxTopic.MESSAGE_ACCEPTED.topicName());
        }
        Map<UUID, Long> perTenantPublished = new LinkedHashMap<>();
        broker.forEach(r -> perTenantPublished.merge(byId.get(r.outboxId()).tenantId(), 1L, Long::sum));
        assertThat(perTenantPublished.get(OutboxChaosDatabase.TENANT_A)).isEqualTo(perTenant);
        assertThat(perTenantPublished.get(OutboxChaosDatabase.TENANT_B)).isEqualTo(perTenant);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The seam the lanes fill: their {@code @Component}/{@code @Bean}s under {@code io.integrationplatform.ingest}
     * (see the package javadoc of this chaos package). Red until then, with a message naming the task.
     */
    private <T> T requireIntegrated(Class<T> type, String lane, String taskId) {
        T bean = context.getBeanProvider(type).getIfAvailable();
        assertThat(bean)
                .as("%s (%s) is not integrated yet: no %s bean in the io.integrationplatform.ingest scan. "
                        + "This chaos suite is written test-first and turns green when %s lands its implementation.",
                        lane, taskId, type.getSimpleName(), taskId)
                .isNotNull();
        return bean;
    }

    /** The Given of every scenario: messages whose rows the real writer commits inside a tenant-bound transaction. */
    private List<Seeded> seed(UUID tenantId, int count, OutboxWriter writer) {
        return seed(tenantId, count, -1, writer);
    }

    private List<Seeded> seed(UUID tenantId, int count, int ordinalHint, OutboxWriter writer) {
        tenantContext.bind(tenantId);
        try {
            List<Seeded> seeded = new ArrayList<>(count);
            String prefix = "chaos-" + tenantId.toString().substring(0, 8) + (ordinalHint < 0 ? "" : "-" + ordinalHint);
            transaction.executeWithoutResult(status -> {
                for (int i = 0; i < count; i++) {
                    UUID messageId = UUID.randomUUID();
                    String key = prefix + (ordinalHint < 0 ? "-" + i : "") + "-k";
                    long id = writer.append(new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, messageId, key, null, null));
                    seeded.add(new Seeded(id, tenantId, messageId, key));
                }
            });
            return seeded;
        } finally {
            tenantContext.clear();
        }
    }

    /** Restarted cycles until the relay reports nothing left to mark; returns the sum of marked rows. */
    private int drain(OutboxRelay relay) {
        int marked = 0;
        for (int cycle = 1; cycle <= MAX_DRAIN_CYCLES; cycle++) {
            int n = relay.runOnce();
            assertThat(n).as("cycle %d returns its marked-row count (the seam contract)", cycle)
                    .isGreaterThanOrEqualTo(0);
            marked += n;
            if (n == 0) {
                return marked;
            }
        }
        throw new IllegalStateException(
                "relay did not finish within " + MAX_DRAIN_CYCLES + " cycles - a re-publish loop, not slow CI");
    }

    private void feedConsumer(List<OutboundRecord> broker) {
        broker.forEach(consumer::onReceive);
    }
}
