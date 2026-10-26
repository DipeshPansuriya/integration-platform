package io.integrationplatform.e9.archfixture.bulkhead.compliant;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.bulkhead.BulkheadRegistry;
import java.util.UUID;

/** T-177 compliant fixture: a stage listener handler that hands its work to the target's bulkhead, never the call. */
public final class HandlerThroughBulkhead implements StageConsumer.Handler {

    private final BulkheadRegistry bulkheads;

    public HandlerThroughBulkhead(BulkheadRegistry bulkheads) {
        this.bulkheads = bulkheads;
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        bulkheads.of(tenantId);
    }
}
