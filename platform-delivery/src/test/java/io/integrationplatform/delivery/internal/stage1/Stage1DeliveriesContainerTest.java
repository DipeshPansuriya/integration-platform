package io.integrationplatform.delivery.internal.stage1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.delivery.DeliveryTestDatabase;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.Stage1Delivery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@link io.integrationplatform.delivery.port.Stage1Deliveries} port on the real partitioned tables (T-117
 * + T-178; ADR-014 section 2, accepted by Dipesh 2026-10-12): the fan-out insert with the deterministic day
 * ({@code created_at = accepted_at} - never a fresh instant), the unique triple
 * {@code (message_id, route_id, created_at)} as the database-level idempotency key, one
 * {@code platform.delivery.due} outbox row per NEWLY inserted delivery, and the pin columns. The
 * {@code ACCEPTED -> ROUTED} consumer record (the same message consumed twice, the guard in the same
 * transaction) is E9-F1-S1's stage-1 consumer proof on the routing side - the message table lands with the E8
 * wave-1 merge, and the test waits on that (the brief's gate).
 */
@Tag("container")
class Stage1DeliveriesContainerTest {

    /** Today's day partition always exists (the migration pre-creates CURRENT_DATE-1..+7 at container start). */
    private static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS);
    // instance fields (JUnit builds one test instance per method): the class shares the database and today's
    // partition with every other method, so each method gets its own pins - fixed pins leaked stage-1 rows
    // from one method into the next once the insert landed
    private final UUID MESSAGE = UUID.randomUUID();
    private final UUID OTHER_MESSAGE = UUID.randomUUID();
    private final UUID ROUTE_A = UUID.randomUUID();
    private final UUID ROUTE_B = UUID.randomUUID();
    private final UUID ROUTE_C = UUID.randomUUID();
    private final UUID TARGET_B = UUID.randomUUID();
    private final UUID MAP3 = UUID.randomUUID();
    private final UUID MAP7 = UUID.randomUUID();
    private static final UUID INTERFACE = UUID.randomUUID();

    private final DeliveryTestDatabase db = DeliveryTestDatabase.get();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private DeliveryTestDatabase.DeliveryTx tx;

    @BeforeEach
    void setUp() {
        pool = db.appPool(4);
        jdbc = JdbcClient.create(pool);
        tx = DeliveryTestDatabase.tx(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private static Stage1Delivery stage1Delivery(UUID messageId, UUID routeId, UUID targetId,
            UUID mappingVersionId) {
        return new Stage1Delivery(messageId, routeId, DAY, targetId, mappingVersionId, contractOf(mappingVersionId),
                "tenant|interface|K", INTERFACE, "K", 7L);
    }

    /** The target contract version pinned beside a mapping version (BS-10): any stable value per mapping version. */
    private static UUID contractOf(UUID mappingVersionId) {
        return mappingVersionId == null ? null
                : UUID.nameUUIDFromBytes(("contract-of-" + mappingVersionId).getBytes());
    }

    private JdbcStage1Deliveries stage1(UUID tenant) {
        return new JdbcStage1Deliveries(new JdbcDeliveryRepository(jdbc, DeliveryTestDatabase.tenant(tenant)));
    }

    private List<Map<String, Object>> deliveryRows(UUID tenant, UUID messageId) {
        return tx.inTenantTx(tenant, () -> jdbc.sql(
                        "select state, created_at, message_id, route_id, mapping_version_id, target_id,"
                                + " interface_id, business_key, key_seq, contract_version_id"
                                + " from public.delivery where message_id = :message_id::uuid order by route_id")
                .param("message_id", messageId.toString())
                .query((rs, n) -> Map.<String, Object>of(
                        "state", rs.getString(1),
                        "created_at", rs.getObject(2),
                        "message_id", String.valueOf(rs.getObject(3)),
                        "route_id", String.valueOf(rs.getObject(4)),
                        "mapping_version_id", String.valueOf(rs.getObject(5)),
                        "target_id", String.valueOf(rs.getObject(6)),
                        "interface_id", String.valueOf(rs.getObject(7)),
                        "business_key", String.valueOf(rs.getObject(8)),
                        "key_seq", String.valueOf(rs.getObject(9)),
                        "contract_version_id", String.valueOf(rs.getObject(10))))
                .list());
    }

    private List<Map<String, Object>> dueOutboxRows(UUID tenant, UUID aggregateId) {
        return tx.inTenantTx(tenant, () -> jdbc.sql(
                        "select topic, aggregate_id, record_key, key_bucket, published_at, contract_version_id"
                                + " from public.outbox where aggregate_id = :aggregate_id::uuid")
                .param("aggregate_id", aggregateId.toString())
                .query((rs, n) -> Map.<String, Object>of(
                        "topic", rs.getString(1),
                        "aggregate_id", String.valueOf(rs.getObject(2)),
                        "record_key", rs.getString(3),
                        "key_bucket", rs.getInt(4),
                        "published_at", rs.getObject(5) == null ? "null" : "published",
                        "contract_version_id", String.valueOf(rs.getObject(6))))
                .list());
    }

    @Test
    void theFanOutInsertsExactlyOnePendingDeliveryPerRouteWithItsPinColumns() {
        int inserted = tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> stage1(DeliveryTestDatabase.TENANT_A)
                .insertPerRoute(List.of(
                        stage1Delivery(MESSAGE, ROUTE_A, DeliveryTestDatabase.TARGET_ID, MAP3),
                        stage1Delivery(MESSAGE, ROUTE_B, TARGET_B, MAP7))));
        assertThat(inserted).isEqualTo(2);

        List<Map<String, Object>> rows = deliveryRows(DeliveryTestDatabase.TENANT_A, MESSAGE);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("state", "PENDING")
                    .containsEntry("message_id", MESSAGE.toString());
            assertThat(row).containsEntry("created_at", java.sql.Timestamp.from(DAY)); // the message's accepted day - ADR-014
            // the message's ordering scope rides on every delivery (ADR-011 Decision 6): without it the due-claim's
            // earlier-delivery clause and the best-effort hold compare NULLs and never match (E9 reopen)
            assertThat(row).containsEntry("interface_id", INTERFACE.toString())
                    .containsEntry("business_key", "K")
                    .containsEntry("key_seq", "7");
        });
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("route_id", ROUTE_A.toString())
                .containsEntry("mapping_version_id", MAP3.toString())
                .containsEntry("contract_version_id", contractOf(MAP3).toString()) // BS-10: operator replay needs it
                .containsEntry("target_id", DeliveryTestDatabase.TARGET_ID.toString()));
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("route_id", ROUTE_B.toString())
                .containsEntry("mapping_version_id", MAP7.toString())
                .containsEntry("contract_version_id", contractOf(MAP7).toString())
                .containsEntry("target_id", TARGET_B.toString()));
    }

    /** The delivery ids stage 1 created for one message (the DB makes the ids; the records carry them). */
    private List<UUID> deliveryIds(UUID tenant, UUID messageId) {
        return tx.inTenantTx(tenant, () -> jdbc.sql("select id from public.delivery where message_id ="
                        + " :message_id::uuid order by route_id")
                .param("message_id", messageId.toString())
                .query((rs, n) -> UUID.fromString(rs.getString(1))).list());
    }

    @Test
    void reConsumingTheSameFanOutCreatesNoNewDeliveryAndNoNewOutboxRow() {
        List<Stage1Delivery> batch = List.of(
                stage1Delivery(MESSAGE, ROUTE_A, DeliveryTestDatabase.TARGET_ID, MAP3),
                stage1Delivery(MESSAGE, ROUTE_B, TARGET_B, MAP7));
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> stage1(DeliveryTestDatabase.TENANT_A).insertPerRoute(batch))).isEqualTo(2);
        List<UUID> afterFirst = deliveryIds(DeliveryTestDatabase.TENANT_A, MESSAGE);
        assertThat(afterFirst).hasSize(2);

        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> stage1(DeliveryTestDatabase.TENANT_A).insertPerRoute(batch)))
                .as("re-consuming after a crash or a rebalance changes nothing (ADR fix m-817272, B2)")
                .isEqualTo(0);
        assertThat(deliveryRows(DeliveryTestDatabase.TENANT_A, MESSAGE)).hasSize(2);
        assertThat(deliveryIds(DeliveryTestDatabase.TENANT_A, MESSAGE)).containsExactlyInAnyOrderElementsOf(afterFirst);
        // one due record in total, for the only NEW delivery - the re-consume added none
        assertThat(dueOutboxRows(DeliveryTestDatabase.TENANT_A, afterFirst.get(0))).hasSize(1);
        assertThat(dueOutboxRows(DeliveryTestDatabase.TENANT_A, afterFirst.get(1))).hasSize(1);
    }

    @Test
    void aPartialReconsumeInsertsOnlyTheMissingRoute() {
        List<Stage1Delivery> first = List.of(
                stage1Delivery(MESSAGE, ROUTE_A, DeliveryTestDatabase.TARGET_ID, MAP3),
                stage1Delivery(MESSAGE, ROUTE_B, TARGET_B, MAP7));
        List<Stage1Delivery> second = List.of(
                stage1Delivery(MESSAGE, ROUTE_A, DeliveryTestDatabase.TARGET_ID, MAP3),
                stage1Delivery(MESSAGE, ROUTE_C, DeliveryTestDatabase.TARGET_ID, MAP3));
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> stage1(DeliveryTestDatabase.TENANT_A).insertPerRoute(first))).isEqualTo(2);
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> stage1(DeliveryTestDatabase.TENANT_A).insertPerRoute(second))).isEqualTo(1);
        assertThat(deliveryRows(DeliveryTestDatabase.TENANT_A, MESSAGE)).hasSize(3);
    }

    @Test
    void oneDueOutboxRowPerNewDeliveryWithTheDeliveryStableRecordKey() {
        int inserted = tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> stage1(DeliveryTestDatabase.TENANT_A)
                .insertPerRoute(List.of(stage1Delivery(MESSAGE, ROUTE_A, DeliveryTestDatabase.TARGET_ID, MAP3))));
        assertThat(inserted).isEqualTo(1);
        UUID deliveryId = deliveryIds(DeliveryTestDatabase.TENANT_A, MESSAGE).get(0);

        List<Map<String, Object>> rows = dueOutboxRows(DeliveryTestDatabase.TENANT_A, deliveryId);
        assertThat(rows).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("topic", "platform.delivery.due")
                .containsEntry("aggregate_id", deliveryId.toString())
                .containsEntry("record_key", "delivery:" + deliveryId) // the T-090 re-drive key convention
                .containsEntry("published_at", "null") // published_at stays unset until the relay marks it
                .containsEntry("contract_version_id", "null"));
        assertThat((int) rows.get(0).get("key_bucket")).isBetween(0, 63); // ADR-005 Decision 11
    }

    @Test
    void aMappingPinWithoutAContractPinIsRefusedByTheRepositoryAndByTheDatabaseCheck() {
        Stage1Delivery unpinned = new Stage1Delivery(MESSAGE, ROUTE_A, DAY, UUID.randomUUID(), MAP3, null,
                "tenant|interface|K", INTERFACE, "K", 7L);

        assertThatThrownBy(() -> tx.inTenantTx(DeliveryTestDatabase.TENANT_A,
                () -> stage1(DeliveryTestDatabase.TENANT_A).insertPerRoute(List.of(unpinned))))
                .hasStackTraceContaining("without a contract version");

        // the CHECK (V2_4_1370) refuses the same pair written around the repository
        assertThatThrownBy(() -> tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "insert into public.delivery (id, tenant_id, state, created_at, updated_at, target_id,"
                                + " mapping_version_id, message_id, route_id) values (gen_random_uuid(),"
                                + " current_setting('app.tenant_id')::uuid, 'PENDING', :at, :at, gen_random_uuid(),"
                                + " :mapping::uuid, gen_random_uuid(), gen_random_uuid())")
                .param("at", java.sql.Timestamp.from(DAY)).param("mapping", MAP3.toString()).update()))
                .hasStackTraceContaining("delivery_contract_pin_check");
        // a route without a mapping needs no contract pin at insert
        assertThat(tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> stage1(DeliveryTestDatabase.TENANT_A)
                .insertPerRoute(List.of(stage1Delivery(MESSAGE, ROUTE_B, UUID.randomUUID(), null))))).isEqualTo(1);
    }

    @Test
    void theUniqueTripleIsADatabaseConstraintThatRejectsADuplicateRawInsert() {
        // ADR-014 section 2, verbatim: the index exists with the settled name
        String indexDef = tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "select indexdef from pg_indexes where indexname = 'delivery_message_route_unique'")
                .query(String.class).optional()).orElse(null);
        assertThat(indexDef).as("the unique triple index of V2_3_930 (ADR-014 section 2)").isNotNull();
        assertThat(indexDef).contains("(message_id, route_id, created_at)");

        // even a raw INSERT (the guard bypassed) cannot create a second row for the triple
        tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "insert into public.delivery (tenant_id, state, created_at, updated_at, target_id,"
                                + " message_id, route_id)"
                                + " values (current_setting('app.tenant_id')::uuid, 'PENDING', :created_at,"
                                + " :created_at, :target_id::uuid, :message_id::uuid, :route_id::uuid)")
                .params(Map.of("created_at", java.sql.Timestamp.from(DAY),
                        "target_id", DeliveryTestDatabase.TARGET_ID.toString(),
                        "message_id", OTHER_MESSAGE.toString(), "route_id", ROUTE_A.toString()))
                .update());
        assertThatThrownBy(() -> tx.inTenantTx(DeliveryTestDatabase.TENANT_A, () -> jdbc.sql(
                        "insert into public.delivery (tenant_id, state, created_at, updated_at, target_id,"
                                + " message_id, route_id)"
                                + " values (current_setting('app.tenant_id')::uuid, 'PENDING', :created_at,"
                                + " :created_at, :target_id::uuid, :message_id::uuid, :route_id::uuid)")
                .params(Map.of("created_at", java.sql.Timestamp.from(DAY),
                        "target_id", DeliveryTestDatabase.TARGET_ID.toString(),
                        "message_id", OTHER_MESSAGE.toString(), "route_id", ROUTE_A.toString()))
                .update())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}