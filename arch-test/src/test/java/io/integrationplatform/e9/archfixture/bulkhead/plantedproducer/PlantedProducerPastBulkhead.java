package io.integrationplatform.e9.archfixture.bulkhead.plantedproducer;

import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.WorkRecord;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;

/** T-177 planted fixture: a stage listener handler that sends through Camel on the consumer thread, past the bulkhead. */
public final class PlantedProducerPastBulkhead implements StageConsumer.Handler {

    private final ProducerTemplate producer;

    public PlantedProducerPastBulkhead(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public void onWork(WorkRecord record, UUID tenantId) {
        producer.sendBody("direct:planted", record);
    }
}
