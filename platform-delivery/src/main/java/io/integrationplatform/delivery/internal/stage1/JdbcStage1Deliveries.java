package io.integrationplatform.delivery.internal.stage1;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.ingest.port.StageTimings;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@link Stage1Deliveries} port on the real tables (T-117 + T-178, ADR-014): the fan-out insert with the
 * deterministic day ({@code created_at = accepted_at}) and the {@code ON CONFLICT (message_id, route_id,
 * created_at) DO NOTHING} idempotency, one {@code platform.delivery.due} outbox row per NEWLY inserted delivery,
 * and the conditional {@code ACCEPTED -> ROUTED} guard. Runs inside the caller's open, tenant-bound transaction
 * (R08, R11) - the stage-1 consumer calls it behind the {@code OrderedStateChange} helper, which holds the
 * ordering-key lock first.
 */
public final class JdbcStage1Deliveries implements Stage1Deliveries {

    private final JdbcDeliveryRepository repository;
    private final StageTimings timings;

    /** Records no stage timing - for a database without the message family (this module's own tests). */
    public JdbcStage1Deliveries(JdbcDeliveryRepository repository) {
        this.repository = repository;
        this.timings = null;
    }

    /** T105: the {@code ACCEPTED -> ROUTED} guard also writes the message's ROUTE row in its transaction. */
    public JdbcStage1Deliveries(JdbcDeliveryRepository repository, StageTimings timings) {
        this.repository = repository;
        this.timings = Objects.requireNonNull(timings, "timings");
    }

    @Override
    public int insertPerRoute(Collection<Stage1Delivery> deliveries) {
        return repository.stage1InsertPerRoute(deliveries);
    }

    @Override
    public boolean markMessageRouted(UUID messageId) {
        return repository.markMessageRouted(messageId);
    }
}