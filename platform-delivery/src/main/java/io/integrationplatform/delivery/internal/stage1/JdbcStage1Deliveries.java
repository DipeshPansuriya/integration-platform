package io.integrationplatform.delivery.internal.stage1;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import java.util.Collection;
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

    public JdbcStage1Deliveries(JdbcDeliveryRepository repository) {
        this.repository = repository;
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