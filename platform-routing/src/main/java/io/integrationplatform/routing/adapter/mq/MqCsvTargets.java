package io.integrationplatform.routing.adapter.mq;

import java.util.Optional;
import java.util.UUID;

/** Read port over an MQ-CSV endpoint's stored settings and contract (the registry slice lands the JDBC impl). */
@FunctionalInterface
public interface MqCsvTargets {

    /** The tenant's endpoint row for {@code endpointId}, or empty when the endpoint is not this tenant's. */
    Optional<MqCsvTarget> byEndpointId(UUID endpointId);

    /**
     * What the send needs from the target's row: its connection settings, the CSV contract parsed from the
     * registry JSON ({@link CsvContractSettings#fromJson}) and the connect credentials, which the credential
     * slice supplies later ({@code user} may be null; {@code password} is zeroed by the send path after use).
     */
    record MqCsvTarget(MqSettings settings, String user, char[] password, CsvContract contract) {
    }
}
