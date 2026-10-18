package io.integrationplatform.routing.adapter.mq;

import java.util.Optional;
import java.util.UUID;

/** The registry read: the current tenant's MQ system settings, or empty when the system is not this tenant's. */
@FunctionalInterface
public interface MqSettingsViews {

    Optional<MqSettings> bySystemId(UUID systemId);
}
