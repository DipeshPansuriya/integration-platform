package io.integrationplatform.tenancy.port;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The narrow projection the login bootstrap returns (ADR-009 Decision 3): user id and status, tenant id, code, status
 * and time zone - nothing else.
 */
public record LoginBinding(
        UUID userId, String userStatus, UUID tenantId, String tenantCode, String tenantStatus, ZoneId timeZone) {

    public LoginBinding {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(userStatus, "userStatus");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantCode, "tenantCode");
        Objects.requireNonNull(tenantStatus, "tenantStatus");
        Objects.requireNonNull(timeZone, "timeZone");
    }
}
