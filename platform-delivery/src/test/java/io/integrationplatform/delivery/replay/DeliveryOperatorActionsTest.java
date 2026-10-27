package io.integrationplatform.delivery.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.replay.DeliveryOperatorActions.Outcome;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions.Result;
import io.integrationplatform.domain.audit.AuditActions;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E13 N2 (T111, T-166 narrow): the rules of the operator retry and replay, without Docker - the store is an in-memory
 * fake over the bound tenant's rows, the outbox and the event publisher record what they get.
 * <ol>
 *   <li>replay moves a DEAD_LETTERED delivery to DUE and re-drives it (one {@code platform.delivery.due} record)
 *       with the mapping and contract versions it was created with;</li>
 *   <li>retry moves a RETRY_WAIT delivery to DUE now, the same way;</li>
 *   <li>each action publishes exactly one audit event: actor, tenant, delivery id and the pinned versions;</li>
 *   <li>a delivery the bound tenant cannot see (another tenant's) is NOT_FOUND and nothing changes;</li>
 *   <li>the wrong source state, or a mapped delivery without its contract pin, changes nothing and audits nothing;</li>
 *   <li>a route WITHOUT a mapping is replayable (T172, project-owner decision 2026-10-22): its contract pin comes from the target
 *       endpoint's active contract, written onto the delivery; none active means NOT_PINNED;</li>
 *   <li>with no tenant bound nothing runs.</li>
 * </ol>
 */
class DeliveryOperatorActionsTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private static final String ACTOR = "6d1f0c1e-0000-4000-8000-00000000a111";

    private final FakeStore store = new FakeStore();
    private final List<OutboxEntry> outbox = new ArrayList<>();
    private final List<Object> published = new ArrayList<>();
    /** The active contract version of a target endpoint (the replay pin for a route without a mapping). */
    private final Map<UUID, UUID> targets = new HashMap<>();
    private final DeliveryOperatorActions actions = actions(() -> Optional.of(TENANT));

    private DeliveryOperatorActions actions(TenantContext tenants) {
        return new DeliveryOperatorActions(store, target -> Optional.ofNullable(targets.get(target)), entry -> {
            outbox.add(entry);
            return outbox.size();
        }, published::add, tenants, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void replayMovesADeadLetteredDeliveryToDueAndReDrivesItWithItsPinnedVersions() {
        UUID id = UUID.randomUUID();
        UUID mapping = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        store.put(id, DeliveryState.DEAD_LETTERED, mapping, contract);

        Result result = actions.replay(id, ACTOR);

        assertThat(result).isEqualTo(new Result(Outcome.MOVED_TO_DUE, id, DeliveryState.DUE, mapping, contract));
        assertThat(store.state(id)).isEqualTo(DeliveryState.DUE);
        assertThat(store.moves).containsExactly("replay " + id + " @" + NOW);
        assertThat(outbox).containsExactly(
                new OutboxEntry(OutboxTopic.DELIVERY_DUE, id, "delivery:" + id, null, null, contract));
        assertThat(published).containsExactly(AuditActions.operatorReplay(ACTOR, TENANT, id, mapping, contract, NOW));
    }

    @Test
    void retryMovesARetryWaitDeliveryToDueNowAndReDrivesItWithItsPinnedVersions() {
        UUID id = UUID.randomUUID();
        UUID mapping = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        store.put(id, DeliveryState.RETRY_WAIT, mapping, contract);

        Result result = actions.retry(id, ACTOR);

        assertThat(result).isEqualTo(new Result(Outcome.MOVED_TO_DUE, id, DeliveryState.DUE, mapping, contract));
        assertThat(store.moves).containsExactly("retry " + id + " @" + NOW);
        assertThat(outbox).containsExactly(
                new OutboxEntry(OutboxTopic.DELIVERY_DUE, id, "delivery:" + id, null, null, contract));
        assertThat(published).containsExactly(AuditActions.operatorRetry(ACTOR, TENANT, id, mapping, contract, NOW));
    }

    @Test
    void eachActionPublishesExactlyOneAuditEventWithActorTenantDeliveryAndPins() {
        UUID id = UUID.randomUUID();
        UUID mapping = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        store.put(id, DeliveryState.DEAD_LETTERED, mapping, contract);

        actions.replay(id, ACTOR);

        assertThat(published).hasSize(1);
        AuditEvent event = (AuditEvent) published.get(0);
        assertThat(event.who()).isEqualTo(ACTOR);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.action()).isEqualTo(AuditActions.OPERATOR_REPLAY);
        assertThat(event.targetIds()).containsExactly("delivery:" + id, "mapping_version:" + mapping,
                "contract_version:" + contract);
    }

    @Test
    void aDeliveryTheBoundTenantCannotSeeIsNotFoundAndNothingChanges() {
        UUID foreign = UUID.randomUUID(); // another tenant's row: the tenant-scoped store never returns it

        assertThat(actions.replay(foreign, ACTOR).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(actions.retry(foreign, ACTOR).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertNothingChanged();
    }

    @Test
    void theWrongSourceStateChangesNothing() {
        UUID waiting = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        UUID delivered = UUID.randomUUID();
        store.put(waiting, DeliveryState.RETRY_WAIT, UUID.randomUUID(), UUID.randomUUID());
        store.put(dead, DeliveryState.DEAD_LETTERED, UUID.randomUUID(), UUID.randomUUID());
        store.put(delivered, DeliveryState.DELIVERED, UUID.randomUUID(), UUID.randomUUID());

        Result replayOfWaiting = actions.replay(waiting, ACTOR);
        Result retryOfDead = actions.retry(dead, ACTOR);
        Result replayOfDelivered = actions.replay(delivered, ACTOR);

        assertThat(replayOfWaiting.outcome()).isEqualTo(Outcome.WRONG_STATE);
        assertThat(replayOfWaiting.state()).isEqualTo(DeliveryState.RETRY_WAIT);
        assertThat(retryOfDead.outcome()).isEqualTo(Outcome.WRONG_STATE);
        assertThat(retryOfDead.state()).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(replayOfDelivered.outcome()).isEqualTo(Outcome.WRONG_STATE);
        assertNothingChanged();
    }

    @Test
    void aDeliveryWithAMappingButNoContractPinIsRefusedAndNothingChanges() {
        UUID noContract = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        store.put(noContract, DeliveryState.DEAD_LETTERED, UUID.randomUUID(), null, target);
        targets.put(target, UUID.randomUUID()); // an active contract exists, but a mapped delivery is never re-pinned

        assertThat(actions.replay(noContract, ACTOR).outcome()).isEqualTo(Outcome.NOT_PINNED);
        assertThat(store.state(noContract)).isEqualTo(DeliveryState.DEAD_LETTERED);
        assertThat(store.pins).isEmpty();
        assertNothingChanged();
    }

    @Test
    void replayOfARouteWithoutAMappingPinsTheTargetsActiveContractAndMovesToDue() {
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        store.put(id, DeliveryState.DEAD_LETTERED, null, null, target);
        targets.put(target, active);

        Result result = actions.replay(id, ACTOR);

        assertThat(result).isEqualTo(new Result(Outcome.MOVED_TO_DUE, id, DeliveryState.DUE, null, active));
        assertThat(store.pins).containsExactly(id + "=" + active);
        assertThat(outbox).containsExactly(
                new OutboxEntry(OutboxTopic.DELIVERY_DUE, id, "delivery:" + id, null, null, active));
        assertThat(published).containsExactly(AuditActions.operatorReplay(ACTOR, TENANT, id, null, active, NOW));
    }

    @Test
    void aMappinglessDeliveryThatAlreadyHasAContractReplaysWithoutResolvingAgain() {
        UUID id = UUID.randomUUID();
        UUID pinned = UUID.randomUUID();
        store.put(id, DeliveryState.RETRY_WAIT, null, pinned, UUID.randomUUID());

        assertThat(actions.retry(id, ACTOR).contractVersionId()).isEqualTo(pinned);
        assertThat(store.pins).isEmpty();
    }

    @Test
    void aRouteWithoutAMappingAndWithoutAnActiveContractIsRefusedAndNothingChanges() {
        UUID id = UUID.randomUUID();
        store.put(id, DeliveryState.DEAD_LETTERED, null, null, UUID.randomUUID());

        assertThat(actions.replay(id, ACTOR).outcome()).isEqualTo(Outcome.NOT_PINNED);
        assertThat(store.pins).isEmpty();
        assertNothingChanged();
    }

    @Test
    void nothingRunsWithoutABoundTenant() {
        UUID id = UUID.randomUUID();
        store.put(id, DeliveryState.DEAD_LETTERED, UUID.randomUUID(), UUID.randomUUID());
        DeliveryOperatorActions unbound = actions(Optional::empty);

        assertThatThrownBy(() -> unbound.replay(id, ACTOR)).isInstanceOf(NoTenantBoundException.class);
        assertThatThrownBy(() -> unbound.retry(id, ACTOR)).isInstanceOf(NoTenantBoundException.class);
        assertThat(store.locks).isZero();
        assertNothingChanged();
    }

    private void assertNothingChanged() {
        assertThat(store.moves).isEmpty();
        assertThat(outbox).isEmpty();
        assertThat(published).isEmpty();
    }

    /** The bound tenant's rows; a conditional move matches only the expected source state (ADR-010 section 7). */
    private static final class FakeStore implements OperatorActionStore {

        private final Map<UUID, Target> rows = new HashMap<>();
        private final List<String> moves = new ArrayList<>();
        private final List<String> pins = new ArrayList<>();
        private int locks;

        void put(UUID id, DeliveryState state, UUID mapping, UUID contract) {
            put(id, state, mapping, contract, null);
        }

        void put(UUID id, DeliveryState state, UUID mapping, UUID contract, UUID target) {
            rows.put(id, new Target(id, state, mapping, contract, target));
        }

        @Override
        public boolean pinContract(UUID deliveryId, UUID contractVersionId) {
            Target row = rows.get(deliveryId);
            if (row == null || row.contractVersionId() != null) {
                return false;
            }
            rows.put(deliveryId, new Target(deliveryId, row.state(), row.mappingVersionId(), contractVersionId,
                    row.targetEndpointId()));
            pins.add(deliveryId + "=" + contractVersionId);
            return true;
        }

        DeliveryState state(UUID id) {
            return rows.get(id).state();
        }

        @Override
        public Optional<Target> lockForAction(UUID deliveryId) {
            locks++;
            return Optional.ofNullable(rows.get(deliveryId));
        }

        @Override
        public boolean replay(UUID deliveryId, Instant at) {
            return move(deliveryId, DeliveryState.DEAD_LETTERED, "replay", at);
        }

        @Override
        public boolean retryNow(UUID deliveryId, Instant at) {
            return move(deliveryId, DeliveryState.RETRY_WAIT, "retry", at);
        }

        private boolean move(UUID id, DeliveryState from, String kind, Instant at) {
            Target row = rows.get(id);
            if (row == null || row.state() != from) {
                return false;
            }
            rows.put(id, new Target(id, DeliveryState.DUE, row.mappingVersionId(), row.contractVersionId(),
                    row.targetEndpointId()));
            moves.add(kind + " " + id + " @" + at);
            return true;
        }
    }
}
