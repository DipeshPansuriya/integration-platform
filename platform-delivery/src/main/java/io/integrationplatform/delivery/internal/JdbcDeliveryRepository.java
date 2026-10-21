package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.delivery.ordering.OrderingKey;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The delivery tables' only writer (T-012, T-013, T-193): the delivery row, the append-only attempt ledger and
 * the {@code delivery_event} log, all through the tenant-scoped base (R08, R09, R11). Runs in the caller's open,
 * tenant-bound transaction and never opens its own.
 *
 * <p>Every state change is a CONDITIONAL update (ADR-010 section 7): the WHERE carries the expected source state
 * and, after a claim, the claim token - 0 matched rows means "no effect" and is reported as {@code false},
 * never as an exception. A transition that matched writes its {@code delivery_event} row with its time in the
 * same transaction ("each transition is stored with its time", E2-F2-S1). Appends of attempts and events are
 * INSERT ... SELECT under the same tenant predicate (the ledger may only be extended, R15: the base grants the
 * application role UPDATE and DELETE on nothing here).
 *
 * <p>E9 additions (T-117, T-178, T-165, T-197): the ordering helpers the {@code OrderedStateChange} port calls
 * (the tenant stamp of the helper's own transaction, the advisory key lock, the message and delivery row locks);
 * the stage-1 fan-out with the UNIQUE triple {@code (message_id, route_id, created_at)} (ADR-014 section 2) and
 * the acceptance of the {@code ACCEPTED -> ROUTED} guard; and the FINAL-state release - a delivered or
 * dead-lettered delivery releases its held same-scope successors IN THE SAME TRANSACTION, each release writing
 * its own {@code platform.delivery.due} outbox record (the in-repository due-record writer mirrors the
 * transactional-outbox pattern of the T-090 re-drive).
 */
public final class JdbcDeliveryRepository extends TenantScopedRepository {

    private final TenantContext tenantContext;

    public JdbcDeliveryRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.tenantContext = tenantContext;
    }

    // ------------------------------------------------------------------ the delivery row

    /** The stage-1 insert (ADR-010 section 6 row "(new) -> PENDING"); {@code keySeq} is null when ordering is NONE. */
    public void create(UUID id, UUID interfaceId, String businessKey, Long keySeq, UUID targetId,
            Instant createdAt) {
        int rows = update(
                "insert into public.delivery (id, tenant_id, state, created_at, updated_at, interface_id,"
                        + " business_key, key_seq, target_id)"
                        + " select :id::uuid, :own_tenant::uuid, 'PENDING', :created_at, :created_at,"
                        + " :interface_id::uuid, :business_key, :key_seq, :target_id::uuid"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null,
                params("id", id.toString(), "own_tenant", tenantContext.requireTenantId().toString(),
                        "created_at", createdAt, "interface_id", interfaceId == null ? null : interfaceId.toString(),
                        "business_key", businessKey, "key_seq", keySeq,
                        "target_id", targetId == null ? null : targetId.toString()));
        requireSingle(rows, "create delivery " + id);
    }

    public Optional<DeliveryState> state(UUID id) {
        return first(query("select state from public.delivery", "id = :id::uuid",
                params("id", id.toString()), (rs, n) -> DeliveryState.valueOf(rs.getString(1))));
    }

    public Optional<Instant> nextAttemptAt(UUID id) {
        return query("select next_attempt_at from public.delivery", "id = :id::uuid",
                params("id", id.toString()), (rs, rowNum) -> instant(rs, 1)).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** Every delivery id of the bound tenant (the tenant filter of the base is the point of this test hook). */
    public List<UUID> findIds() {
        return query("select id from public.delivery", null, Map.of(),
                (rs, n) -> UUID.fromString(rs.getString(1)));
    }

    // ------------------------------------------------------------------ transitions (ADR-010 section 6)

    /**
     * {@code PENDING/DUE -> SENDING} with a fresh claim token; the claim transaction's step 4. The transition also
     * resets {@code bulkhead_deferrals} to 0 (T71-C1, E9-plan section 54: "a successful claim resets the count" -
     * the claim-side half of the {@link io.integrationplatform.delivery.port.BulkheadDeferrals} contract; lane A's
     * T-198 deferral path reads the reset value as its consecutive-deferral input).
     */
    public boolean claimSend(UUID id, UUID claimToken, Instant at) {
        return transition(
                "update public.delivery set state = 'SENDING', updated_at = :at, claim_token = :claim_token::uuid,"
                        + " claimed_at = :at, next_attempt_at = null, bulkhead_deferrals = 0",
                "id = :id::uuid and state in ('PENDING', 'DUE')",
                params("at", at, "claim_token", claimToken.toString(), "id", id.toString()),
                id, DeliveryEventType.CLAIM, null, at);
    }

    /** {@code PENDING/DUE -> HELD} when the claim's hold check applies (step 3), with the {@code HOLD} transition. */
    public boolean hold(UUID id, Instant at) {
        return transition(
                "update public.delivery set state = 'HELD', updated_at = :at, claim_token = null,"
                        + " claimed_at = null, next_attempt_at = null",
                "id = :id::uuid and state in ('PENDING', 'DUE')",
                params("at", at, "id", id.toString()),
                id, DeliveryEventType.HELD, null, at);
    }

    /** {@code HELD -> DUE}: the blockers are gone (ADR-010 section 6, release). */
    public boolean release(UUID id, Instant at) {
        return transition(
                "update public.delivery set state = 'DUE', updated_at = :at, next_attempt_at = null",
                "id = :id::uuid and state = 'HELD'",
                params("at", at, "id", id.toString()),
                id, DeliveryEventType.RELEASE, null, at);
    }

    /** {@code RETRY_WAIT -> DUE}: the stored retry time was reached (the delivery-retry job's re-drive). */
    public boolean redrive(UUID id, Instant at) {
        return transition(
                "update public.delivery set state = 'DUE', updated_at = :at, next_attempt_at = null",
                "id = :id::uuid and state = 'RETRY_WAIT'",
                params("at", at, "id", id.toString()),
                id, DeliveryEventType.REDRIVE, null, at);
    }

    /** {@code SENDING -> RETRY_WAIT} with the new retry time, conditional on the claim token. */
    public boolean scheduleRetry(UUID id, UUID claimToken, Instant nextAttemptAt, Instant at) {
        return transition(
                "update public.delivery set state = 'RETRY_WAIT', updated_at = :at,"
                        + " next_attempt_at = :next_attempt_at, claim_token = null, claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "next_attempt_at", nextAttemptAt,
                        "claim_token", claimToken.toString(), "id", id.toString()),
                id, DeliveryEventType.RETRY, "retry at " + nextAttemptAt, at);
    }

    /** {@code SENDING -> DELIVERED}, conditional on the claim token (the classifier's success outcome). */
    public boolean markDelivered(UUID id, UUID claimToken, Instant at) {
        return finalOutcome(id, DeliveryEventType.DELIVER, null,
                "update public.delivery set state = 'DELIVERED', updated_at = :at, claim_token = null,"
                        + " claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "claim_token", claimToken.toString(), "id", id.toString()), at);
    }

    /** {@code SENDING -> DEAD_LETTERED} with the reason class, conditional on the claim token. */
    public boolean deadLetter(UUID id, UUID claimToken, OutcomeClass reasonClass, Instant at) {
        return finalOutcome(id, DeliveryEventType.DEAD_LETTER, reasonClass.name(),
                "update public.delivery set state = 'DEAD_LETTERED', updated_at = :at,"
                        + " dead_letter_class = :dead_letter_class, claim_token = null, claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "dead_letter_class", reasonClass.name(),
                        "claim_token", claimToken.toString(), "id", id.toString()), at);
    }

    /**
     * E5 T-185: {@code SENDING -> DEAD_LETTERED} with the reason class AND the named reason in the event detail
     * ({@code PERMANENT: target credential unreadable}), conditional on the claim token.
     */
    public boolean deadLetter(UUID id, UUID claimToken, OutcomeClass reasonClass, String reason, Instant at) {
        return finalOutcome(id, DeliveryEventType.DEAD_LETTER, reasonClass.name() + ": " + reason,
                "update public.delivery set state = 'DEAD_LETTERED', updated_at = :at,"
                        + " dead_letter_class = :dead_letter_class, claim_token = null, claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "dead_letter_class", reasonClass.name(),
                        "claim_token", claimToken.toString(), "id", id.toString()), at);
    }

    /**
     * E5 T-185 (ADR-010 sections 5 and 6): {@code SENDING -> RETRY_WAIT} because the target credential's key is not on
     * this instance - conditional on the claim token, a {@code KEY_UNAVAILABLE} event, and no attempt row.
     */
    public boolean deferKeyUnavailable(UUID id, UUID claimToken, Instant nextAttemptAt, Instant at) {
        return transition(
                "update public.delivery set state = 'RETRY_WAIT', updated_at = :at,"
                        + " next_attempt_at = :next_attempt_at, claim_token = null, claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "next_attempt_at", nextAttemptAt,
                        "claim_token", claimToken.toString(), "id", id.toString()),
                id, DeliveryEventType.KEY_UNAVAILABLE, "retry at " + nextAttemptAt, at);
    }

    /** E5 T-185: how many events of {@code type} the delivery has (the consecutive key deferrals). */
    public int countEvents(UUID deliveryId, DeliveryEventType type) {
        return query("select count(*)::int from public.delivery_event",
                "delivery_id = :delivery_id::uuid and type = :type",
                params("delivery_id", deliveryId.toString(), "type", type.name()), (rs, n) -> rs.getInt(1)).stream()
                .findFirst().orElse(0);
    }

    /** {@code DEAD_LETTERED -> DUE}: an operator replay (audited where the operator acts). */
    public boolean replay(UUID id, Instant at) {
        return transition(
                "update public.delivery set state = 'DUE', updated_at = :at, dead_letter_class = null",
                "id = :id::uuid and state = 'DEAD_LETTERED'",
                params("at", at, "id", id.toString()),
                id, DeliveryEventType.REPLAY, null, at);
    }

    private boolean transition(String updateFragment, String condition, Map<String, Object> parameters, UUID id,
            DeliveryEventType event, String eventDetail, Instant at) {
        int rows = update(updateFragment, condition, parameters);
        if (rows == 0) {
            return false;
        }
        appendEvent(id, event, eventDetail, at);
        return true;
    }

    // ------------------------------------------------------------------ the append-only ledgers

    /** Appends one attempt row (R15: an append - the table grants no UPDATE or DELETE, and there is none here). */
    public void recordAttempt(UUID deliveryId, int layer, OutcomeClass outcomeClass, Instant startedAt,
            Instant finishedAt, String detail) {
        int rows = update(
                "insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at,"
                        + " finished_at, detail)"
                        + " select d.tenant_id, d.id, :layer, :outcome_class, :started_at, :finished_at,"
                        + " :detail from public.delivery d",
                "d.id = :delivery_id::uuid",
                params("layer", (short) layer, "outcome_class", outcomeClass.name(), "started_at", startedAt,
                        "finished_at", finishedAt, "detail", detail, "delivery_id", deliveryId.toString()));
        requireSingle(rows, "append attempt for delivery " + deliveryId);
    }

    /**
     * The layer-2 attempt budget count (ADR-010 section 4, section 6 addendum M5): the attempt rows of the CURRENT
     * series - those started after the delivery's latest {@code REPLAY} event, or every row when it was never
     * replayed. A {@code REDRIVE} does not start a series. The ledger itself keeps every row (R15); "retries made"
     * is the lifetime count, not this one.
     */
    public int countAttempts(UUID deliveryId) {
        return query("select count(*)::int from public.delivery_attempt a",
                "delivery_id = :delivery_id::uuid and a.started_at > coalesce((select max(e.occurred_at)"
                        + " from public.delivery_event e where e.delivery_id = a.delivery_id"
                        + " and e.tenant_id = a.tenant_id and e.type = 'REPLAY'), '-infinity'::timestamptz)",
                params("delivery_id", deliveryId.toString()), (rs, n) -> rs.getInt(1)).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("count returned no row"));
    }

    /** Appends one event row; true when the delivery exists under the bound tenant. */
    public boolean appendEvent(UUID deliveryId, DeliveryEventType type, String detail, Instant at) {
        return update(
                "insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at)"
                        + " select d.tenant_id, d.id, :type, :detail, :occurred_at from public.delivery d",
                "d.id = :delivery_id::uuid",
                params("type", type.name(), "detail", detail, "occurred_at", at,
                        "delivery_id", deliveryId.toString())) == 1;
    }

    // ------------------------------------------------------------------ the claim primitives (T-193)

    /** A delivery row readable by the claim, locked {@code FOR UPDATE} in the caller's transaction. */
    public Optional<Claimable> readForClaim(UUID id) {
        return first(query(
                "select scoped.id, scoped.interface_id, scoped.business_key, scoped.key_seq, scoped.target_id from ("
                        + " select d.id, d.interface_id, d.business_key, d.key_seq, d.target_id, d.tenant_id"
                        + " from public.delivery d"
                        + " where d.tenant_id = :tenant_id::uuid and d.id = :id::uuid"
                        + " and d.state in ('PENDING', 'DUE') for update) scoped",
                null, params("id", id.toString()),
                (rs, n) -> new Claimable(uuid(rs, 1), uuid(rs, 2), rs.getString(3),
                        rs.getObject(4, Long.class), uuid(rs, 5))));
    }

    /**
     * ADR-011 Decision 6 clause 2: an earlier delivery (lower per-key sequence) to the same target endpoint in
     * the same scope that is not final. Clause 1 (an earlier message still {@code ACCEPTED}) joins this check
     * when the message table lands (E3/E4). A null key sequence is an {@code ordering: NONE} interface - never
     * held.
     */
    public boolean holdApplies(Claimable row) {
        if (row.keySeq() == null) {
            return false;
        }
        return !query(
                "select scoped.tenant_id from (select d.tenant_id, exists ("
                        + " select 1 from public.delivery b where b.tenant_id = :tenant_id::uuid"
                        + " and b.interface_id = :interface_id::uuid and b.business_key = :business_key"
                        + " and b.target_id = :target_id::uuid and b.key_seq < :key_seq"
                        + " and b.state not in ('DELIVERED', 'DEAD_LETTERED')) as blocked"
                        + " from public.delivery d where d.id = :id::uuid) scoped",
                "blocked",
                params("interface_id", string(row.interfaceId()), "business_key", row.businessKey(),
                        "target_id", string(row.targetId()), "key_seq", row.keySeq(), "id", string(row.id())),
                (rs, n) -> Boolean.TRUE).isEmpty();
    }

    /** The claim's input row: the ordering scope of a delivery locked FOR UPDATE. */
    public record Claimable(UUID id, UUID interfaceId, String businessKey, Long keySeq, UUID targetId) {
    }

    // ------------------------------------------------------------------ E9: the one lock order (T-197)

    /**
     * Stamps the RLS tenant of the helper's own transaction ({@code set_config local}, R11) - the caller's
     * stamp runs once per caller transaction and the helper opened this one, so the stamp is ours to set.
     */
    public void stampTenant() {
        query("select set_config('app.tenant_id', :own_tenant, true) as stamped, scoped.tenant_id"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null,
                params("own_tenant", tenantContext.requireTenantId().toString()),
                (rs, n) -> Boolean.TRUE).forEach(unused -> {
                });
    }

    /**
     * The FIRST lock of the one order (ADR-010 section 7; ADR-011 Decision 6 "one lock, one order"): the
     * transaction-level advisory lock on the ordering scope's record key, held to commit.
     */
    public void lockOrderingKey(String orderingKey) {
        query("select pg_advisory_xact_lock(hashtextextended(:ordering_key, 0))"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null,
                params("ordering_key", orderingKey, "own_tenant", tenantContext.requireTenantId().toString()),
                (rs, n) -> Boolean.TRUE).forEach(unused -> {
                });
    }

    /** {@code true} while the message table of E8 lane A's wave-1 migration ({@code V2_1_810+}) is deployed. */
    private boolean messageTablePresent() {
        return query("select to_regclass('public.message') is not null as present, scoped.tenant_id"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null,
                params("own_tenant", tenantContext.requireTenantId().toString()),
                (rs, n) -> rs.getBoolean(1)).stream().findFirst().orElse(false);
    }

    /**
     * The SECOND lock of the one order: the message row, {@code FOR UPDATE}. On a wave-0 database there is no
     * message table yet (the stage-1 migration of E8 lane A is the wave-1 gate) - the lock is a no-op there and
     * the same call becomes real the day the table lands. A row of another tenant matches nothing (locked or
     * not - the base's tenant predicate is on the read).
     */
    public void lockMessageRow(UUID messageId) {
        if (!messageTablePresent()) {
            return; // wave-0: nothing to lock (the stage-1 message table is E8 lane A's wave-1 gate)
        }
        query("select scoped.id, scoped.tenant_id from ("
                        + " select m.id, m.tenant_id from public.message m"
                        + " where m.id = :message_id::uuid for update) scoped",
                null, params("message_id", messageId.toString()),
                (rs, n) -> rs.getString(1)).forEach(unused -> {
                });
    }

    /**
     * The THIRD lock of the one order: the affected deliveries, each {@code FOR UPDATE}, in the caller's
     * ascending id order - one row lock per statement, ids already sorted by the helper's support.
     */
    public void lockDeliveryRows(List<UUID> idsAscending) {
        for (UUID id : idsAscending) {
            query("select scoped.id, scoped.tenant_id from ("
                            + " select d.id, d.tenant_id from public.delivery d"
                            + " where d.id = :delivery_id::uuid for update) scoped",
                    null, params("delivery_id", id.toString()),
                    (rs, n) -> rs.getString(1)).forEach(unused -> {
                    });
        }
    }

    /** The ordering scope of a delivery row (read without a lock; null when no such row for the bound tenant). */
    public Scope readScope(UUID id) {
        return first(query(
                "select scoped.id, scoped.interface_id, scoped.business_key, scoped.key_seq, scoped.target_id,"
                        + " scoped.message_id, scoped.tenant_id from ("
                        + " select d.id, d.interface_id, d.business_key, d.key_seq, d.target_id,"
                        + " d.message_id, d.tenant_id from public.delivery d where d.id = :id::uuid) scoped",
                null, params("id", id.toString()),
                (rs, n) -> new Scope(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getObject(4, Long.class),
                        uuid(rs, 5), uuid(rs, 6)))).orElse(null);
    }

    /** The scope a final state releases: the ordering fields plus the message pin of the stage-1 fan-out. */
    public record Scope(UUID deliveryId, UUID interfaceId, String businessKey, Long keySeq, UUID targetId,
            UUID messageId) {
    }

    /**
     * The one lock order for a FINAL state (T-165): the ordering-key advisory lock derived from the row's own
     * scope (the same {@link OrderingKey} derivation every lane calls), then the message row. The delivery row
     * itself is locked by the conditional update that follows.
     */
    private void lockForFinalState(Scope scope) {
        if (scope.interfaceId() != null && scope.businessKey() != null) {
            lockOrderingKey(OrderingKey.recordKeyFor(tenantContext.requireTenantId(), scope.interfaceId(),
                    scope.businessKey()));
        }
        if (scope.messageId() != null) { // no stage-1 pin (E2 insert, pre-V2_3_930 rows): no message row to lock
            lockMessageRow(scope.messageId()); // as the one-order helper does (JdbcOrderedStateChange)
        }
    }

    // ------------------------------------------------------------------ E9: the stage-1 write (T-117 + T-178)

    /**
     * The stage-1 fan-out (ADR-014 section 2): one PENDING delivery per named route, {@code created_at =
     * accepted_at} (the message's accepted day - ADR-014 Decision 1, never a fresh instant), idempotent by
     * {@code ON CONFLICT (message_id, route_id, created_at) DO NOTHING} against the UNIQUE triple index. Each
     * NEWLY inserted delivery gets exactly one {@code platform.delivery.due} outbox record in the same
     * transaction. The insert also writes the message's ordering scope (interface_id, business_key, key_seq -
     * ADR-011 Decision 6) so {@code delivery_ordering_idx} and the per-key ordering gate see the row.
     */
    public int stage1InsertPerRoute(Collection<Stage1Delivery> deliveries) {
        int inserted = 0;
        for (Stage1Delivery delivery : deliveries) {
            List<UUID> ids = query(
                    "with ins as ("
                            + " insert into public.delivery"
                            + "   (tenant_id, state, created_at, updated_at, target_id, message_id, route_id,"
                            + "    mapping_version_id, interface_id, business_key, key_seq)"
                            + " values (:tenant_id::uuid, 'PENDING', :accepted_at, :accepted_at,"
                            + "   :target_endpoint_id::uuid, :message_id::uuid, :route_id::uuid,"
                            + "   :mapping_version_id::uuid, :interface_id::uuid, :business_key, :key_seq::bigint)"
                            + " on conflict (message_id, route_id, created_at) do nothing"
                            + " returning id, tenant_id)"
                            + " select ins.id, ins.tenant_id from ins",
                    null,
                    params("accepted_at", delivery.acceptedAt(),
                            "target_endpoint_id",
                            delivery.targetEndpointId() == null ? null : delivery.targetEndpointId().toString(),
                            "message_id", delivery.messageId().toString(),
                            "route_id", delivery.routeId().toString(),
                            "mapping_version_id",
                            delivery.mappingVersionId() == null ? null : delivery.mappingVersionId().toString(),
                            "interface_id", delivery.interfaceId() == null ? null : delivery.interfaceId().toString(),
                            "business_key", delivery.businessKey(),
                            "key_seq", delivery.keySeq()),
                    (rs, n) -> uuid(rs, 1));
            if (ids.size() > 1) {
                throw new IllegalStateException("stage-1 insert matched " + ids.size() + " rows (never expected)");
            }
            if (!ids.isEmpty()) {
                appendDeliveryDue(ids.get(0));
                inserted++;
            }
        }
        return inserted;
    }

    /** The {@code ACCEPTED -> ROUTED} guard in the caller's transaction (ADR-014 section 1). */
    public boolean markMessageRouted(UUID messageId) {
        if (!messageTablePresent()) {
            throw new IllegalStateException("the message table is E8 lane A's wave-1 migration (V2_1_810+) -"
                    + " the ACCEPTED -> ROUTED guard waits for that merge (ADR-014 section 1, fail-closed)");
        }
        return update(
                "update public.message set state = 'ROUTED', routed_at = now(), updated_at = :at",
                "id = :message_id::uuid and state = 'ACCEPTED'",
                params("at", Instant.now(), "message_id", messageId.toString())) == 1;
    }

    /**
     * ONE due record for a newly-due delivery: topic {@code platform.delivery.due}, record key
     * {@code delivery:<id>} (the T-090 re-drive convention, mirrored by the retry trigger), the ADR-005 Decision
     * 11 key bucket, {@code published_at} left unset until the relay marks it. Runs in the caller's transaction.
     */
    private void appendDeliveryDue(UUID deliveryId) {
        int rows = query(
                "with src as ("
                        + " select d.id, d.tenant_id from public.delivery d where d.id = :delivery_id::uuid"
                        + "), ins as ("
                        + " insert into public.outbox (tenant_id, topic, aggregate_id, record_key, key_bucket,"
                        + "   traceparent)"
                        + " select src.tenant_id, :topic, src.id, :record_key, :key_bucket,"
                        + "   cast(:traceparent as text) from src"
                        + " returning id, tenant_id)"
                        + " select ins.id, ins.tenant_id from ins",
                null,
                params("delivery_id", deliveryId.toString(), "topic", OutboxTopic.DELIVERY_DUE.topicName(),
                        "record_key", "delivery:" + deliveryId, "key_bucket", keyBucket("delivery:" + deliveryId),
                        // T107: the deliver hop continues the journey that wrote it (the relay restores the header)
                        "traceparent", CorrelationContext.currentTraceparent()),
                (rs, n) -> rs.getString(1)).size();
        requireSingle(rows, "append due record for delivery " + deliveryId);
    }

    /** ponytail: mirror of platform-ingest KeyBuckets (package-private there) - ADR-005 Decision 11. */
    private static int keyBucket(String recordKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(recordKey.getBytes(StandardCharsets.UTF_8))[0] & 63;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by ADR-005 Decision 11", impossible);
        }
    }

    // ------------------------------------------------------------------ E9: the final state releases (T-165)

    /**
     * A FINAL state (delivered or dead-lettered, T-165): the row's scope is locked first (the one order), then
     * the conditional transition runs, and when it MATCHED the held same-scope successors are released in the
     * same transaction - each release writes its {@code RELEASE} event and its due record, so the release rolls
     * back together with the final state. {@code false} (no effect, never an exception) when the row does not
     * exist or the transition matched nothing (a stale or foreign claim token - E2-F2-S4).
     */
    private boolean finalOutcome(UUID id, DeliveryEventType event, String eventDetail, String updateFragment,
            String condition, Map<String, Object> parameters, Instant at) {
        Scope scope = readScope(id);
        if (scope == null) {
            return false; // no such delivery for the bound tenant: nothing changed
        }
        lockForFinalState(scope);
        if (!transition(updateFragment, condition, parameters, id, event, eventDetail, at)) {
            return false; // 0 rows: the state was not SENDING behind this token - only the transition is a no-op
        }
        releaseHeldInScope(scope, at);
        return true;
    }

    /** Releases the held same-scope successors the final state unblocked, in key-sequence order. */
    private void releaseHeldInScope(Scope scope, Instant at) {
        for (UUID held : heldSiblingsNoLongerBlocked(scope)) {
            if (release(held, at)) { // HELD -> DUE with the RELEASE event
                appendDeliveryDue(held); // the due record that re-drives it - same transaction (ADR-011 D6)
            }
        }
    }

    /**
     * The HELD deliveries of the final row's (interface, business key, target) scope that no longer have an
     * earlier non-final delivery of that scope - ADR-011 Decision 6's hold, evaluated after the final state.
     */
    private List<UUID> heldSiblingsNoLongerBlocked(Scope scope) {
        if (scope.interfaceId() == null || scope.businessKey() == null || scope.targetId() == null) {
            return List.of();
        }
        return query("select held.id, held.tenant_id from ("
                        + " select h.id, h.tenant_id from public.delivery h"
                        + " where h.state = 'HELD' and h.interface_id = :interface_id::uuid"
                        + " and h.business_key = :business_key and h.target_id = :target_id::uuid"
                        + " and h.key_seq is not null and not exists ("
                        + "   select 1 from public.delivery b"
                        + "   where b.tenant_id = h.tenant_id and b.interface_id = h.interface_id"
                        + "   and b.business_key = h.business_key and b.target_id = h.target_id"
                        + "   and b.key_seq < h.key_seq and b.state not in ('DELIVERED', 'DEAD_LETTERED'))"
                        + " order by h.key_seq, h.id) held",
                null,
                params("interface_id", scope.interfaceId().toString(), "business_key", scope.businessKey(),
                        "target_id", scope.targetId().toString()),
                (rs, n) -> uuid(rs, 1));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            map.put((String) pairs[i], value instanceof Instant instant
                    ? java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : value);
        }
        return map;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private static void requireSingle(int rows, String what) {
        if (rows != 1) {
            throw new IllegalStateException(what + " matched " + rows + " rows (tenant bound: the row must exist)");
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static UUID uuid(ResultSet rs, int column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }
}
