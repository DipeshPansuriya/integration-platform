package io.integrationplatform.delivery.internal.stage1;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.Stage1Deliveries;
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

    // RED stub (T-117/T-178): inserts nothing until the green commit - the container test's 0 rows are its red.
    @Override
    public int insertPerRoute(Collection<io.integrationplatform.delivery.port.Stage1Delivery> deliveries) {
        return 0;
    }

    // RED stub (T-178): the ACCEPTED -> ROUTED guard lands with the green commit and the wave-1 message table.
    @Override
    public boolean markMessageRouted(UUID messageId) {
        return false;
    }
}