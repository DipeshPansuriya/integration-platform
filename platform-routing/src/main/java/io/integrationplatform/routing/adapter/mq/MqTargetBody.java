package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** The MQ send's rendered body for a delivery (row 327 port); empty means no body, so the send is refused PERMANENT. */
@FunctionalInterface
public interface MqTargetBody {

    Optional<byte[]> bodyFor(Stage2Send send);
}
