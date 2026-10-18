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

    /**
     * The endpoint version of ADR-010 section 3 (F16): a deterministic fingerprint of the values a route instance
     * is built from - target type, endpoint URI, pinned contract and mapping versions. Equal rows give the same
     * version on every consuming instance; any change gives a new one. The first 16 hex characters of a SHA-256,
     * so the route id and the direct consumer that carry it never expose the endpoint URI.
     */
    public String endpointVersion() {
        String material = targetType + '\u001f' + endpointUri + '\u001f' + contractVersionId + '\u001f'
                + mappingVersionId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    /** The cache key this row's route instance is held under (target endpoint, endpoint version). */
    public RouteKey key() {
        return new RouteKey(targetEndpointId, endpointVersion());
    }
}
