package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** Resolves a due delivery to its MQ message; empty means the delivery has nothing sendable (PERMANENT). */
@FunctionalInterface
public interface MqOutbound {

    Optional<MqMessage> resolve(Stage2Send send);
}
