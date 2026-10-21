package io.integrationplatform.config.destination;

import java.util.Map;
import java.util.UUID;

/**
 * T-126: the MQ test-connection port. Config owns the endpoint; the MQ adapter (routing) answers it with the same
 * fixed eight-key shape as the API check. Lives here because routing may depend on {@code config.destination} only.
 */
@FunctionalInterface
public interface MqConnectionCheck {

    Map<String, Object> test(UUID systemId, String settingsJson);
}
