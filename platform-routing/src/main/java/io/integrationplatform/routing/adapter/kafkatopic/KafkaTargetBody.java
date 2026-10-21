package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** The KAFKA send's rendered body for a delivery (row 330 port); empty means no body, so the send is refused PERMANENT. */
@FunctionalInterface
public interface KafkaTargetBody {

    Optional<byte[]> bodyFor(Stage2Send send);
}
