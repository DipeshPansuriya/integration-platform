package io.integrationplatform.routing.templates;

import java.util.UUID;

/**
 * One route's read configuration rows (T-116): the values a route instance is instantiated from - the
 * published interface-to-target binding with its row's target type, the endpoint URI (a scheme + parameters,
 * never free text, ADR-010 section 3) and the versions pinned at instantiation (E9-C3: later configuration
 * changes never alter a delivery's pinned versions - only new messages use new versions).
 *
 * @param routeId           the configuration route's id (the instance keeps it as its identity on this instance)
 * @param targetEndpointId  the row's target endpoint
 * @param targetType        the row's target type (one route template per target type, ADR-010 section 3)
 * @param endpointUri       the row's endpoint URI, scheme + parameters (validated against {@code allowedSchemes}
 *                          when the route is built)
 * @param contractVersionId the contract version pinned at instantiation
 * @param mappingVersionId  the mapping version pinned at instantiation
 */
public record RouteConfigRow(UUID routeId, UUID targetEndpointId, String targetType, String endpointUri,
        UUID contractVersionId, UUID mappingVersionId) {

    /** The URI's scheme - the part {@code allowedSchemes} guards. */
    public String scheme() {
        String uri = endpointUri;
        int colon = uri.indexOf(':');
        if (colon <= 0) {
            return ""; // no scheme at all - refused by the guard like any other unapproved scheme
        }
        return uri.substring(0, colon).toLowerCase(java.util.Locale.ROOT).trim();
    }
}