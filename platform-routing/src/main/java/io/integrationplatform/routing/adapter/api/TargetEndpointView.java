package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.net.URI;
import java.util.Map;

/**
 * One endpoint's send settings as the adapter uses them (E10 narrow) - the read view over
 * {@code target_endpoint} (V2_2_1). The endpoint's own error dictionary re-classs its statuses here; the
 * classifier decides whether the override is allowed.
 *
 * @param baseUri the endpoint's base address (scheme, host, port, base path)
 * @param httpMethod the endpoint's method (GET/POST/PUT/PATCH/DELETE, V2_2_1's CHECK)
 * @param timeoutMs the whole-call timeout (100..300000, the CHECK's range)
 * @param authMethod NO_AUTH in this slice; a credential-based method is refused before any call (E10 credentials)
 * @param idempotentReceive the target's own declaration - it moves AMBIGUOUS to TRANSIENT
 * @param errorDictionary the endpoint's per-status overrides (status -> outcome class name)
 */
public record TargetEndpointView(URI baseUri, String httpMethod, int timeoutMs, String authMethod,
        boolean idempotentReceive, Map<Integer, OutcomeClass> errorDictionary) {

    public TargetEndpointView {
        java.util.Objects.requireNonNull(baseUri, "baseUri");
        java.util.Objects.requireNonNull(httpMethod, "httpMethod");
        java.util.Objects.requireNonNull(authMethod, "authMethod");
        java.util.Objects.requireNonNull(errorDictionary, "errorDictionary");
    }
}