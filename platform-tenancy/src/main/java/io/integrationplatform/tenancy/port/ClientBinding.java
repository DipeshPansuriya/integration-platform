package io.integrationplatform.tenancy.port;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The narrow projection the client bootstrap returns (ADR-007 Decision 2, the machine twin of {@link LoginBinding}):
 * one source-system credential resolves to one source system, which belongs to one tenant and carries one customer
 * identity. Only ACTIVE credentials of ACTIVE tenants are ever returned.
 *
 * @param credentialId     the {@code source_credential} row
 * @param credentialStatus always {@code ACTIVE} when returned by the bootstrap
 * @param sourceSystemId   the registered source system the credential was issued to
 * @param customerId       the customer identity the credential carries - the only source of a source call's customer
 * @param tenantId         the tenant that owns the source system
 * @param tenantCode       the tenant's code
 * @param tenantStatus     always {@code ACTIVE} when returned by the bootstrap
 * @param timeZone         the tenant's time zone
 */
public record ClientBinding(UUID credentialId, String credentialStatus, UUID sourceSystemId, String customerId,
        UUID tenantId, String tenantCode, String tenantStatus, ZoneId timeZone) {

    public ClientBinding {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(credentialStatus, "credentialStatus");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantCode, "tenantCode");
        Objects.requireNonNull(tenantStatus, "tenantStatus");
        Objects.requireNonNull(timeZone, "timeZone");
    }
}
