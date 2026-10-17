package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E13 N1 (T110, row278 wave): the message timeline and the failures list, read from the real tables over the
 * restricted login. The rows are seeded by the owner (the stage writers beyond INTAKE are T105's), then read through
 * {@link MessageTrackingService} - the bean the endpoints call - inside its tenant-stamped read transaction:
 * <ol>
 *   <li>the timeline by tracking id returns every recorded stage with its timestamps, each stage's duration and the
 *       gap since the previous stage, and the total;</li>
 *   <li>the failures list per flow and UTC day returns each route-failed message and each dead-lettered delivery
 *       with the failing stage, the error class and the last error text - the platform's own (our-side) errors
 *       included and marked {@code PLATFORM}, a target's answer marked {@code TARGET};</li>
 *   <li>tenant B sees nothing of tenant A: no timeline (the endpoint's 404) and none of A's failures;</li>
 *   <li>the timeline names its failing stage from server-side state (T112 round 2, row 328 M1): ROUTE for a
 *       route-failed message, DELIVER for one with a DEAD_LETTERED delivery, null otherwise - and another tenant's
 *       dead-lettered delivery, even one naming this tenant's message id, never marks it failed;</li>
 *   <li>the timeline lists every delivery of the message with the target's response time - the LAST attempt's
 *       sent-to-answered duration and outcome, null without an attempt (row 329 M3) - and another tenant's
 *       deliveries and attempts never appear in it;</li>
 *   <li>with no tenant bound, no read runs at all;</li>
 *   <li>row 328 V4: the store picks each delivery's last attempt in SQL - one row per delivery, even when the
 *       attempts span two daily partitions (finish time first, then attempt id, as the dead-letter read);</li>
 *   <li>row 328 K1: with row-level security BYPASSED (the owner connection) and a tenant bound in the app context,
 *       the app's own tenant predicates alone still hide tenant B's message, failures, deliveries and attempts.</li>
 * </ol>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageTrackingContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-0000000000e1");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-0000000000e1");

    /** One flow id for both tenants on purpose: only the tenant predicate may separate their rows, never the flow. */
    private static final UUID FLOW = UUID.randomUUID();
    private static final UUID OTHER_FLOW = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    // tenant A
    private static final UUID TIMED = UUID.randomUUID();          // ROUTED, four timed stages
    private static final UUID ROUTE_FAILED = UUID.randomUUID();   // our side: routing failed
    private static final UUID CREDENTIAL = UUID.randomUUID();     // ROUTED; its delivery dead-letters on our side
    private static final UUID DELIVERED = UUID.randomUUID();      // ROUTED; its delivery reached the target
    private static final UUID OTHER_FLOW_FAILED = UUID.randomUUID();
    private static final UUID YESTERDAY_FAILED = UUID.randomUUID();
    private static final UUID TARGET_DEAD = UUID.randomUUID();    // delivery of TIMED, the target refused it
    private static final UUID PLATFORM_DEAD = UUID.randomUUID();  // delivery of CREDENTIAL, credential unreadable
    private static final UUID DELIVERED_DELIVERY = UUID.randomUUID();
    // tenant B
    private static final UUID B_ROUTE_FAILED = UUID.randomUUID();
    /** T112 round 2: tenant B's DEAD_LETTERED delivery that names tenant A's DELIVERED message id (forged reference). */
    private static final UUID B_FORGED_DEAD = UUID.randomUUID();
    /** Row 328 V4: tenant A's message whose one delivery was attempted across two daily partitions. */
    private static final UUID RETRIED = UUID.randomUUID();
    private static final UUID RETRIED_DELIVERY = UUID.randomUUID();

    /** Whole milliseconds, a few seconds back: every seeded instant stays inside today's UTC day partitions. */
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(10);
    private static final LocalDate TODAY = LocalDate.ofInstant(T0, ZoneOffset.UTC);

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicInteger SEEDED = new AtomicInteger();

    @Autowired
    private MessageTrackingService tracking;

    @Autowired
    private TestBoundTenantContext boundTenant;

    @Autowired
    private JdbcMessageTrackingStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The tenant is bound explicitly per read; the stamping transaction manager stamps what is bound. */
    @TestConfiguration
    static class TenantBindingConfiguration {

        @Bean
        @Primary
        TestBoundTenantContext testBoundTenantContext() {
            return new TestBoundTenantContext();
        }
    }

    static final class TestBoundTenantContext implements TenantContext {

        private final ThreadLocal<UUID> bound = new ThreadLocal<>();

        <T> T as(UUID tenantId, Supplier<T> read) {
            bound.set(tenantId);
            try {
                return read.get();
            } finally {
                bound.remove();
            }
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    @BeforeEach
    void grantApplicationRoleAndSeed() throws SQLException {
        if (SEEDED.incrementAndGet() != 1) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            run(owner, "insert into public.tenant (id, code, status, time_zone) values"
                    + " (?::uuid, 'tracking-tenant-a', 'ACTIVE', 'Etc/UTC'),"
                    + " (?::uuid, 'tracking-tenant-b', 'ACTIVE', 'Etc/UTC')", TENANT_A, TENANT_B);

            message(owner, TENANT_A, TIMED, FLOW, "ROUTED", T0, T0.plusMillis(600));
            // stored out of order on purpose: the timeline orders by start, never by insertion
            stage(owner, TENANT_A, TIMED, T0, "DELIVER", T0.plusMillis(300), T0.plusMillis(500));
            stage(owner, TENANT_A, TIMED, T0, "INTAKE", T0, T0.plusMillis(40));
            stage(owner, TENANT_A, TIMED, T0, "TRANSFORM", T0.plusMillis(200), T0.plusMillis(230));
            stage(owner, TENANT_A, TIMED, T0, "ROUTE", T0.plusMillis(100), T0.plusMillis(160));

            message(owner, TENANT_A, ROUTE_FAILED, FLOW, "ROUTE_FAILED", T0, T0.plusSeconds(1));
            message(owner, TENANT_A, CREDENTIAL, FLOW, "ROUTED", T0, T0.plusMillis(100));
            message(owner, TENANT_A, DELIVERED, FLOW, "ROUTED", T0, T0.plusMillis(100));
            message(owner, TENANT_A, OTHER_FLOW_FAILED, OTHER_FLOW, "ROUTE_FAILED", T0, T0.plusSeconds(1));
            Instant yesterday = T0.minus(1, ChronoUnit.DAYS);
            message(owner, TENANT_A, YESTERDAY_FAILED, FLOW, "ROUTE_FAILED", yesterday, yesterday.plusSeconds(1));

            // the target answered twice; the last answer is the one the operator needs
            delivery(owner, TENANT_A, TARGET_DEAD, TIMED, FLOW, "DEAD_LETTERED", "PERMANENT", T0, T0.plusSeconds(3));
            attempt(owner, TENANT_A, TARGET_DEAD, "TRANSIENT", T0.plusSeconds(1), "HTTP 503 upstream busy");
            attempt(owner, TENANT_A, TARGET_DEAD, "PERMANENT", T0.plusSeconds(2), "HTTP 400 field orderId rejected");
            event(owner, TENANT_A, TARGET_DEAD, "DEAD_LETTER", "PERMANENT", T0.plusSeconds(3));
            // our side: dead-lettered before any send, the named reason rides the DEAD_LETTER event only
            delivery(owner, TENANT_A, PLATFORM_DEAD, CREDENTIAL, FLOW, "DEAD_LETTERED", "PERMANENT", T0,
                    T0.plusSeconds(2));
            event(owner, TENANT_A, PLATFORM_DEAD, "DEAD_LETTER", "PERMANENT: target credential unreadable",
                    T0.plusSeconds(2));
            delivery(owner, TENANT_A, DELIVERED_DELIVERY, DELIVERED, FLOW, "DELIVERED", null, T0, T0.plusSeconds(1));
            attempt(owner, TENANT_A, DELIVERED_DELIVERY, "SUCCESS", T0.plusSeconds(1), "HTTP 200");

            message(owner, TENANT_B, B_ROUTE_FAILED, FLOW, "ROUTE_FAILED", T0, T0.plusSeconds(1));
            stage(owner, TENANT_B, B_ROUTE_FAILED, T0, "INTAKE", T0, T0.plusMillis(25));
            // OTHER_FLOW on purpose: B's failures for FLOW (asserted exactly above) stay B_ROUTE_FAILED alone
            delivery(owner, TENANT_B, B_FORGED_DEAD, DELIVERED, OTHER_FLOW, "DEAD_LETTERED", "PERMANENT", T0,
                    T0.plusSeconds(2));
            // and a tenant-B attempt naming tenant A's delivery, finishing LATER than A's own attempt
            attempt(owner, TENANT_B, DELIVERED_DELIVERY, "PERMANENT", T0.plusSeconds(5), "forged by tenant B");

            // row 328 V4: one delivery, four attempts over yesterday's and today's attempt partitions. The last is
            // SUCCESS at T0+2s: it ties on finish with a TRANSIENT inserted before it (the higher id wins) and beats a
            // TRANSIENT inserted after it that finished earlier (finish time decides before the id).
            message(owner, TENANT_A, RETRIED, FLOW, "ROUTED", yesterday, T0.plusSeconds(2));
            delivery(owner, TENANT_A, RETRIED_DELIVERY, RETRIED, FLOW, "DELIVERED", null, yesterday,
                    T0.plusSeconds(2));
            attempt(owner, TENANT_A, RETRIED_DELIVERY, "TRANSIENT", yesterday.plusSeconds(1), "HTTP 503 night",
                    yesterday.plusSeconds(1));
            attempt(owner, TENANT_A, RETRIED_DELIVERY, "TRANSIENT", T0.plusSeconds(2), "HTTP 503 tie",
                    T0.plusSeconds(2));
            attempt(owner, TENANT_A, RETRIED_DELIVERY, "SUCCESS", T0.plusSeconds(2), "HTTP 200", T0.plusSeconds(2));
            attempt(owner, TENANT_A, RETRIED_DELIVERY, "TRANSIENT", T0.plusSeconds(1), "HTTP 503 morning",
                    T0.plusSeconds(1));
        }
    }

    @Test
    void theTimelineReturnsEveryRecordedStageWithItsTimestampsAndTheDurationsBetweenStages() {
        Optional<MessageTrackingService.Timeline> read = boundTenant.as(TENANT_A, () -> tracking.timeline(TIMED));

        assertThat(read).isPresent();
        MessageTrackingService.Timeline timeline = read.get();
        assertThat(timeline.trackingId()).isEqualTo(TIMED.toString());
        assertThat(timeline.flowId()).isEqualTo(FLOW.toString());
        assertThat(timeline.state()).isEqualTo("ROUTED");
        assertThat(timeline.acceptedAt()).isEqualTo(T0.toString());
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::stage)
                .containsExactly("INTAKE", "ROUTE", "TRANSFORM", "DELIVER");
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::startedAt).containsExactly(
                T0.toString(), T0.plusMillis(100).toString(), T0.plusMillis(200).toString(),
                T0.plusMillis(300).toString());
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::finishedAt).containsExactly(
                T0.plusMillis(40).toString(), T0.plusMillis(160).toString(), T0.plusMillis(230).toString(),
                T0.plusMillis(500).toString());
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::durationMs)
                .containsExactly(40L, 60L, 30L, 200L);
        assertThat(timeline.stages()).extracting(MessageTrackingService.StageTime::sincePreviousMs)
                .containsExactly(null, 60L, 40L, 70L);
        assertThat(timeline.totalMs()).isEqualTo(500L);
    }

    @Test
    void theFailuresListPerFlowAndDayCarriesTheFailingStageErrorClassAndLastErrorOurSideIncluded() {
        List<MessageTrackingService.Failure> failures =
                boundTenant.as(TENANT_A, () -> tracking.failures(FLOW, TODAY));

        assertThat(failures).extracting(MessageTrackingService.Failure::trackingId).containsExactlyInAnyOrder(
                ROUTE_FAILED.toString(), TIMED.toString(), CREDENTIAL.toString());
        Map<String, MessageTrackingService.Failure> byTracking = failures.stream()
                .collect(Collectors.toMap(MessageTrackingService.Failure::trackingId, Function.identity()));

        MessageTrackingService.Failure routing = byTracking.get(ROUTE_FAILED.toString());
        assertThat(routing.failingStage()).isEqualTo("ROUTE");
        assertThat(routing.errorClass()).isEqualTo("ROUTE_FAILED");
        assertThat(routing.origin()).isEqualTo("PLATFORM");
        assertThat(routing.deliveryId()).isNull();
        assertThat(routing.flowId()).isEqualTo(FLOW.toString());

        MessageTrackingService.Failure refused = byTracking.get(TIMED.toString());
        assertThat(refused.failingStage()).isEqualTo("DELIVER");
        assertThat(refused.deliveryId()).isEqualTo(TARGET_DEAD.toString());
        assertThat(refused.targetId()).isEqualTo(TARGET.toString());
        assertThat(refused.errorClass()).isEqualTo("PERMANENT");
        assertThat(refused.lastError()).isEqualTo("HTTP 400 field orderId rejected");
        assertThat(refused.origin()).isEqualTo("TARGET");
        assertThat(refused.failedAt()).isEqualTo(T0.plusSeconds(3).toString());

        MessageTrackingService.Failure ourSide = byTracking.get(CREDENTIAL.toString());
        assertThat(ourSide.failingStage()).isEqualTo("DELIVER");
        assertThat(ourSide.deliveryId()).isEqualTo(PLATFORM_DEAD.toString());
        assertThat(ourSide.errorClass()).isEqualTo("PERMANENT");
        assertThat(ourSide.lastError()).isEqualTo("PERMANENT: target credential unreadable");
        assertThat(ourSide.origin()).isEqualTo("PLATFORM");
    }

    @Test
    void anotherTenantSeesNoneOfTheMessagesItDoesNotOwn() {
        // positive control first: each tenant reads its own rows
        assertThat(boundTenant.as(TENANT_A, () -> tracking.timeline(TIMED))).isPresent();
        assertThat(boundTenant.as(TENANT_B, () -> tracking.timeline(B_ROUTE_FAILED))).isPresent();
        assertThat(boundTenant.as(TENANT_B, () -> tracking.failures(FLOW, TODAY)))
                .extracting(MessageTrackingService.Failure::trackingId).containsExactly(B_ROUTE_FAILED.toString());

        // then the denial: B never reaches A's timeline, A never reaches B's
        assertThat(boundTenant.as(TENANT_B, () -> tracking.timeline(TIMED))).isEmpty();
        assertThat(boundTenant.as(TENANT_B, () -> tracking.timeline(ROUTE_FAILED))).isEmpty();
        assertThat(boundTenant.as(TENANT_A, () -> tracking.timeline(B_ROUTE_FAILED))).isEmpty();
        assertThat(boundTenant.as(TENANT_A, () -> tracking.failures(FLOW, TODAY)))
                .extracting(MessageTrackingService.Failure::trackingId)
                .doesNotContain(B_ROUTE_FAILED.toString());
    }

    @Test
    void theTimelineNamesTheFailingStageFromServerSideState() {
        assertThat(timelineOf(TENANT_A, TIMED).failingStage()).isEqualTo("DELIVER"); // the target refused it
        assertThat(timelineOf(TENANT_A, CREDENTIAL).failingStage()).isEqualTo("DELIVER"); // our side refused it
        assertThat(timelineOf(TENANT_A, ROUTE_FAILED).failingStage()).isEqualTo("ROUTE");
    }

    @Test
    void anotherTenantsDeadLetterNeverMarksThisTenantsMessageFailed() {
        // tenant B holds a DEAD_LETTERED delivery whose message_id is tenant A's DELIVERED message
        Optional<MessageTrackingService.Timeline> mine = boundTenant.as(TENANT_A, () -> tracking.timeline(DELIVERED));
        assertThat(mine).isPresent();
        assertThat(mine.get().failingStage()).isNull();
        // and A's message stays invisible to B (the endpoint's 404)
        assertThat(boundTenant.as(TENANT_B, () -> tracking.timeline(DELIVERED))).isEmpty();
    }

    @Test
    void theTimelineListsEachDeliveryWithItsLastAttemptsResponseTime() {
        // TIMED's delivery: two target answers, the last (PERMANENT, 50 ms sent-to-answered) is the response
        assertThat(timelineOf(TENANT_A, TIMED).deliveries()).containsExactly(new MessageTrackingService.DeliveryTime(
                TARGET_DEAD.toString(), TARGET.toString(), 50L, "PERMANENT"));
        // CREDENTIAL's delivery dead-lettered before any send: no attempt, no duration
        assertThat(timelineOf(TENANT_A, CREDENTIAL).deliveries()).containsExactly(
                new MessageTrackingService.DeliveryTime(PLATFORM_DEAD.toString(), TARGET.toString(), null, null));
        // a route-failed message never reached a delivery
        assertThat(timelineOf(TENANT_A, ROUTE_FAILED).deliveries()).isEmpty();
    }

    @Test
    void anotherTenantsDeliveriesAndAttemptsNeverAppearInThisTenantsTimeline() {
        // B's forged delivery (naming A's DELIVERED message) and B's later forged attempt (naming A's delivery)
        Optional<MessageTrackingService.Timeline> mine = boundTenant.as(TENANT_A, () -> tracking.timeline(DELIVERED));
        assertThat(mine).isPresent();
        assertThat(mine.get().deliveries()).containsExactly(new MessageTrackingService.DeliveryTime(
                DELIVERED_DELIVERY.toString(), TARGET.toString(), 50L, "SUCCESS"));
        assertThat(boundTenant.as(TENANT_B, () -> tracking.timeline(DELIVERED))).isEmpty();
    }

    @Test
    void noReadRunsWithoutABoundTenant() {
        assertThatThrownBy(() -> tracking.timeline(TIMED)).isInstanceOf(NoTenantBoundException.class);
        assertThatThrownBy(() -> tracking.failures(FLOW, TODAY)).isInstanceOf(NoTenantBoundException.class);
    }

    @Test
    void theStorePicksEachDeliverysLastAttemptInSqlAcrossDailyPartitions() {
        // V4: one row per delivery, not one per attempt - the pick happens in SQL, not in Java
        TransactionTemplate appTx = new TransactionTemplate(transactionManager);
        List<JdbcMessageTrackingStore.DeliveryAttemptRow> rows = inTransaction(appTx, TENANT_A,
                () -> store.deliveries(RETRIED));
        assertThat(rows).hasSize(1);
        JdbcMessageTrackingStore.DeliveryAttemptRow last = rows.get(0);
        assertThat(last.deliveryId()).isEqualTo(RETRIED_DELIVERY.toString());
        assertThat(last.outcomeClass()).isEqualTo("SUCCESS");
        assertThat(last.finishedAt()).isEqualTo(T0.plusSeconds(2));
        // TIMED's delivery had two attempts: still one row, the later one
        List<JdbcMessageTrackingStore.DeliveryAttemptRow> timed = inTransaction(appTx, TENANT_A,
                () -> store.deliveries(TIMED));
        assertThat(timed)
                .extracting(JdbcMessageTrackingStore.DeliveryAttemptRow::outcomeClass).containsExactly("PERMANENT");
        // and the endpoint's answer carries that last attempt
        assertThat(timelineOf(TENANT_A, RETRIED).deliveries()).containsExactly(new MessageTrackingService.DeliveryTime(
                RETRIED_DELIVERY.toString(), TARGET.toString(), 50L, "SUCCESS"));
    }

    @Test
    void withRowLevelSecurityBypassedTheAppsOwnTenantPinStillHidesTenantBsRows() {
        // K1: the container's owner user (the Flyway owner, a superuser) bypasses RLS - the app's predicates alone
        DriverManagerDataSource ownerDb = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcClient ownerClient = JdbcClient.create(ownerDb);
        TransactionTemplate ownerTx = new TransactionTemplate(new DataSourceTransactionManager(ownerDb));
        MessageTrackingService pinOnly = new MessageTrackingService(new JdbcMessageTrackingStore(ownerClient,
                boundTenant));

        // precondition: RLS really is off on this connection - tenant B's rows are visible unfiltered, A bound or not
        Long bAttempts = inTransaction(ownerTx, TENANT_A, () -> ownerClient
                .sql("select count(*) from public.delivery_attempt where tenant_id = :b::uuid")
                .param("b", TENANT_B.toString()).query(Long.class).single());
        Long bMessage = inTransaction(ownerTx, TENANT_A, () -> ownerClient
                .sql("select count(*) from public.message where id = :id::uuid")
                .param("id", B_ROUTE_FAILED.toString()).query(Long.class).single());
        assertThat(bAttempts).isPositive();
        assertThat(bMessage).isEqualTo(1L);

        // positive control: the same pin-only reads find the bound tenant's own rows
        Optional<MessageTrackingService.Timeline> own = inTransaction(ownerTx, TENANT_B,
                () -> pinOnly.timeline(B_ROUTE_FAILED));
        assertThat(own).isPresent();

        // bound to A: B's message is not found (the endpoint's 404) ...
        Optional<MessageTrackingService.Timeline> foreign = inTransaction(ownerTx, TENANT_A,
                () -> pinOnly.timeline(B_ROUTE_FAILED));
        assertThat(foreign).isEmpty();
        // ... none of B's failures is listed, for the flow and day or across all flows and days ...
        List<MessageTrackingService.Failure> flowToday = inTransaction(ownerTx, TENANT_A,
                () -> pinOnly.failures(FLOW, TODAY));
        assertThat(flowToday)
                .extracting(MessageTrackingService.Failure::trackingId).containsExactlyInAnyOrder(
                        ROUTE_FAILED.toString(), TIMED.toString(), CREDENTIAL.toString());
        List<MessageTrackingService.Failure> everyFailure = inTransaction(ownerTx, TENANT_A, () -> pinOnly.failures(null, null));
        assertThat(everyFailure).extracting(MessageTrackingService.Failure::trackingId)
                .doesNotContain(B_ROUTE_FAILED.toString());
        assertThat(everyFailure).extracting(MessageTrackingService.Failure::deliveryId)
                .doesNotContain(B_FORGED_DEAD.toString());
        // ... and B's forged delivery (naming A's message) and later forged attempt (naming A's delivery) stay out
        Optional<MessageTrackingService.Timeline> mine = inTransaction(ownerTx, TENANT_A, () -> pinOnly.timeline(DELIVERED));
        assertThat(mine).isPresent();
        assertThat(mine.get().failingStage()).isNull();
        assertThat(mine.get().deliveries()).containsExactly(new MessageTrackingService.DeliveryTime(
                DELIVERED_DELIVERY.toString(), TARGET.toString(), 50L, "SUCCESS"));
    }

    /** Runs {@code read} in {@code tx} with {@code tenant} bound in the app context. */
    private <T> T inTransaction(TransactionTemplate tx, UUID tenant, Supplier<T> read) {
        return boundTenant.as(tenant, () -> tx.execute(status -> read.get()));
    }

    /** The bound tenant's own timeline, which must exist. */
    private MessageTrackingService.Timeline timelineOf(UUID tenant, UUID trackingId) {
        return boundTenant.as(tenant, () -> tracking.timeline(trackingId)).orElseThrow();
    }

    // ------------------------------------------------------------------ owner-side seeding (RLS bypassed)

    private static void message(Connection owner, UUID tenant, UUID id, UUID flow, String state, Instant acceptedAt,
            Instant updatedAt) throws SQLException {
        run(owner, "insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                + " source_contract_version_id, business_key, state, accepted_at, updated_at)"
                + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)",
                id, tenant, flow, UUID.randomUUID(), UUID.randomUUID(), "order-" + id, state, at(acceptedAt),
                at(updatedAt));
    }

    private static void stage(Connection owner, UUID tenant, UUID message, Instant acceptedAt, String stage,
            Instant startedAt, Instant finishedAt) throws SQLException {
        run(owner, "insert into public.message_stage_timing (message_id, tenant_id, accepted_at, stage, started_at,"
                + " finished_at) values (?::uuid, ?::uuid, ?, ?, ?, ?)",
                message, tenant, at(acceptedAt), stage, at(startedAt), at(finishedAt));
    }

    private static void delivery(Connection owner, UUID tenant, UUID id, UUID message, UUID flow, String state,
            String deadLetterClass, Instant createdAt, Instant updatedAt) throws SQLException {
        run(owner, "insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class,"
                + " interface_id, target_id, message_id) values (?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid,"
                + " ?::uuid)",
                id, tenant, state, at(createdAt), at(updatedAt), deadLetterClass, flow, TARGET, message);
    }

    private static void attempt(Connection owner, UUID tenant, UUID delivery, String outcomeClass,
            Instant finishedAt, String detail) throws SQLException {
        run(owner, "insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at,"
                + " finished_at, detail) values (?::uuid, ?::uuid, 1, ?, ?, ?, ?)",
                tenant, delivery, outcomeClass, at(finishedAt.minusMillis(50)), at(finishedAt), detail);
    }

    /** An attempt stored in the daily partition of {@code createdAt} (the partition key; the default is now()). */
    private static void attempt(Connection owner, UUID tenant, UUID delivery, String outcomeClass,
            Instant finishedAt, String detail, Instant createdAt) throws SQLException {
        run(owner, "insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at,"
                + " finished_at, detail, created_at) values (?::uuid, ?::uuid, 1, ?, ?, ?, ?, ?)",
                tenant, delivery, outcomeClass, at(finishedAt.minusMillis(50)), at(finishedAt), detail,
                at(createdAt));
    }

    private static void event(Connection owner, UUID tenant, UUID delivery, String type, String detail,
            Instant occurredAt) throws SQLException {
        run(owner, "insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at)"
                + " values (?::uuid, ?::uuid, ?, ?, ?)", tenant, delivery, type, detail, at(occurredAt));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void run(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                statement.setObject(i + 1, value instanceof UUID uuid ? uuid.toString() : value);
            }
            statement.executeUpdate();
        }
    }
}
