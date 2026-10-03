package io.integrationplatform.tenancy.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One request that tried to name a tenant other than the bound one, or named a tenant with no identity (E1-F1-S1:
 * "the forgery is recorded as a denied attempt"). The forged value itself is not carried: it is attacker-controlled
 * input and is never logged.
 *
 * @param boundTenantId the tenant bound from the principal, or {@code null} when the request had no identity
 * @param channel       where the forged tenant was carried
 * @param method        the HTTP method
 * @param path          the request path (without the query string)
 * @param at            when the attempt was seen
 */
public record DeniedTenantAttempt(UUID boundTenantId, Channel channel, String method, String path, Instant at) {

    /** Where a request carried a tenant it may not choose (architecture.md section 5, control T1). */
    public enum Channel {
        HEADER,
        QUERY_PARAMETER,
        BODY_FIELD,
        PATH_ID,
        TOKEN_CLAIM,
        NO_IDENTITY
    }

    public DeniedTenantAttempt {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(at, "at");
    }
}
