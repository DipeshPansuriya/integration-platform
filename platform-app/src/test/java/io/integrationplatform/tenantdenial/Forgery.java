package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt.Channel;

/**
 * The examples of the scenario outline "A forged tenant is ignored" (E1-F1-S1), each with the channel the denied
 * attempt must be recorded under (architecture.md section 5, control T1).
 */
enum Forgery {

    HEADER("a \"Tenant\" header", Channel.HEADER),
    BODY_FIELD("a tenantId field in the body", Channel.BODY_FIELD),
    QUERY_PARAMETER("a tenantId query parameter", Channel.QUERY_PARAMETER),
    PATH_ID("a tenant-B record id in the path", Channel.PATH_ID);

    /** The forged header (Gherkin: a "Tenant" header). */
    static final String TENANT_HEADER = "Tenant";

    /** The forged query parameter and body field name (Gherkin: tenantId). */
    static final String TENANT_FIELD = "tenantId";

    private final String example;
    private final Channel channel;

    Forgery(String example, Channel channel) {
        this.example = example;
        this.channel = channel;
    }

    /** The Examples row, word for word. */
    String example() {
        return example;
    }

    Channel channel() {
        return channel;
    }

    /** Header and query parameter fit every endpoint, a body field only a body-accepting one, a path id only {id}. */
    boolean fits(EndpointMatrix.Entry entry) {
        return switch (this) {
            case HEADER, QUERY_PARAMETER -> true;
            case BODY_FIELD -> entry.acceptsBody();
            case PATH_ID -> entry.hasIdPath();
        };
    }

    /** A tenant field an unauthenticated request can carry (scenario "No identity, no tenant"); a path id is not one. */
    boolean isTenantField() {
        return this != PATH_ID;
    }
}
