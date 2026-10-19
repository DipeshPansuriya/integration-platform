package io.integrationplatform.e9.archfixture.bulkhead.planted;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkRecord;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import java.util.UUID;

/** T-177 planted fixture: a stage listener handler that calls the target on the consumer thread, past the bulkhead. */
public final class PlantedCallPastBulkhead implements StageConsumer.Handler {

    private final TargetCall target;

    public PlantedCallPastBulkhead(TargetCall target) {
        this.target = target;
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        target.call(new Stage2Send(tenantId, tenantId, null, tenantId, false));
    }
}
