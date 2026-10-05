package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    /** {@code PENDING/DUE -> SENDING} with a fresh claim token; the claim transaction's step 4. */
    public boolean claimSend(UUID id, UUID claimToken, Instant at) {
        return transition(
                "update public.delivery set state = 'SENDING', updated_at = :at, claim_token = :claim_token::uuid,"
                        + " claimed_at = :at, next_attempt_at = null",
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
        return transition(
                "update public.delivery set state = 'DELIVERED', updated_at = :at, claim_token = null,"
                        + " claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "claim_token", claimToken.toString(), "id", id.toString()),
                id, DeliveryEventType.DELIVER, null, at);
    }

    /** {@code SENDING -> DEAD_LETTERED} with the reason class, conditional on the claim token. */
    public boolean deadLetter(UUID id, UUID claimToken, OutcomeClass reasonClass, Instant at) {
        return transition(
                "update public.delivery set state = 'DEAD_LETTERED', updated_at = :at,"
                        + " dead_letter_class = :dead_letter_class, claim_token = null, claimed_at = null",
                "id = :id::uuid and state = 'SENDING' and claim_token = :claim_token::uuid",
                params("at", at, "dead_letter_class", reasonClass.name(),
                        "claim_token", claimToken.toString(), "id", id.toString()),
                id, DeliveryEventType.DEAD_LETTER, reasonClass.name(), at);
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

    /** The layer-2 attempt count (ADR-010 section 4): how many attempts the ledger holds for the delivery. */
    public int countAttempts(UUID deliveryId) {
        return query("select count(*)::int from public.delivery_attempt", "delivery_id = :delivery_id::uuid",
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

    /**
     * The transaction-level advisory lock on the delivery's ordering key (ADR-010 section 7 step 1, ADR-011
     * Decision 6 "one lock, one order"); held to commit. Skipped by the caller when the interface's ordering
     * is {@code NONE}.
     */
    public void lockOrderingKey(String orderingKey) {
        query("select pg_advisory_xact_lock(hashtextextended(:ordering_key, 0))"
                        + " from (values (:own_tenant::uuid)) as scoped (tenant_id)",
                null,
                params("ordering_key", orderingKey, "own_tenant", tenantContext.requireTenantId().toString()),
                (rs, n) -> Boolean.TRUE).forEach(unused -> {
                });
    }

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
