package io.integrationplatform.tenancy.port;

import java.util.UUID;

/**
 * The work-tenant crossing of the stage consumers (ADR-011 Decision 8; mahesh-architect's ruling m-6f7aa7): a Kafka
 * record carries ids only, so a consumer learns the tenant of the row a record names through the protected definer
 * function {@code ipaas_work_tenant(kind, id)} - never from a record header or value (E8-C4). Only the tenant id
 * leaves the function.
 */
@FunctionalInterface
public interface WorkTenantLookup {

    /**
     * @param kind {@code MESSAGE} (a {@code platform.message.accepted} record) or {@code DELIVERY}
     *     ({@code platform.delivery.due})
     * @return the row's tenant, or {@code null} when no row of that kind has this id
     */
    UUID tenantOf(String kind, UUID id);
}
