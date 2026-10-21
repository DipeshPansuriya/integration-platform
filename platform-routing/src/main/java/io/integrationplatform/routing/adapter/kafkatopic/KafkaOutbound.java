package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** Resolves a due delivery to its Kafka message; empty means the delivery has nothing sendable (PERMANENT). */
@FunctionalInterface
public interface KafkaOutbound {

    Optional<KafkaMessage> resolve(Stage2Send send);
}
