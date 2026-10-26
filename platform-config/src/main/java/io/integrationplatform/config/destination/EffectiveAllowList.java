package io.integrationplatform.config.destination;

import java.util.List;
import java.util.Objects;

/**
 * Mahesh 1f (ADR-010 section 8; Karan's T-202 amendment): the EFFECTIVE destination allow-list is the
 * INTERSECTION of the operator's global list with the tenant's own - the tenant narrows, never widens an
 * operator bound, and the operator's bound stands over every tenant. A missing or empty global list fails
 * closed (T-202): nothing is a destination until the operator names one. Loopback, link-local and the
 * platform's own hosts are refused before any list answers - never allow-listable.
 */
public final class EffectiveAllowList extends AllowList {

    private final AllowList global;
    private final AllowList tenant;

    private EffectiveAllowList(AllowList global, AllowList tenant) {
        super(List.of(), List.of());
        this.global = Objects.requireNonNull(global, "global");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
    }

    /**
     * @param global        the operator's deployment-wide list; empty or missing = everything refused (fail closed)
     * @param tenantAliases the tenant's narrowing patterns; empty or null = the tenant named none (the global
     *                      bound alone stays effective)
     */
    public static AllowList of(AllowList global, List<String> tenantAliases) {
        Objects.requireNonNull(global, "global");
        if (tenantAliases == null || tenantAliases.isEmpty()) {
            return global;
        }
        return new EffectiveAllowList(global, AllowList.of(tenantAliases));
    }

    /** An intersection: both named lists must allow the host - a name is a destination only when both agree. */
    @Override
    public boolean allows(String host) {
        return global.allows(host) && tenant.allows(host);
    }
}