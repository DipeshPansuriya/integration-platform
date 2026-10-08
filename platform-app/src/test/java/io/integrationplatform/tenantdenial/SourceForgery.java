package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt.Channel;

/**
 * The machine channel of the re-run (E4-F1-S4, T-101; ADR-007 Decision 2): a source system of one tenant forges the
 * other tenant in a header, a query parameter or a JSON body field, or names the other tenant's customer in the body.
 * Each is recorded under its channel (architecture.md section 5, controls T1 and T5).
 */
enum SourceForgery {

    HEADER("a \"Tenant\" header", Channel.HEADER),
    QUERY_PARAMETER("a tenantId query parameter", Channel.QUERY_PARAMETER),
    BODY_FIELD("a tenantId field in the body", Channel.BODY_FIELD),
    BODY_CUSTOMER("a customerId of the other tenant's source in the body", Channel.BODY_CUSTOMER);

    /** The body field the common API's customer cross-check reads (ADR-007 Decision 2). */
    static final String CUSTOMER_FIELD = "customerId";

    private final String example;
    private final Channel channel;

    SourceForgery(String example, Channel channel) {
        this.example = example;
        this.channel = channel;
    }

    Channel channel() {
        return channel;
    }

    /** The human outline's forgery this one mirrors; none for the customer cross-check (sources only). */
    Forgery asTenantForgery() {
        return switch (this) {
            case HEADER -> Forgery.HEADER;
            case QUERY_PARAMETER -> Forgery.QUERY_PARAMETER;
            case BODY_FIELD -> Forgery.BODY_FIELD;
            case BODY_CUSTOMER -> null;
        };
    }

    /** Header and query parameter fit every endpoint; a body field or customerId only a body-accepting one. */
    boolean fits(EndpointMatrix.Entry entry) {
        return this == HEADER || this == QUERY_PARAMETER || entry.acceptsBody();
    }

    @Override
    public String toString() {
        return example;
    }
}
