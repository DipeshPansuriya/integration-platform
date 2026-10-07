package io.integrationplatform.tenancy;

/**
 * E1 seam: the one place platform-tenancy's tests name a Testcontainers image (R05: an exact pinned tag). The same tag
 * as platform-app's TestImages and compose/compose.yaml. Every E1 lane's container test uses this constant.
 */
public final class TenancyTestImages {

    /** PostgreSQL 17.11 on Alpine - the tag platform-app's start proof verified. */
    public static final String POSTGRES = "postgres:17.11-alpine";

    /**
     * Keycloak 26.8.0 (E4, T-097; ADR-007 Decision 1 left the version UNVERIFIED - the newest stable tag that
     * docker pull confirmed on 2026-10-07, for mahesh-architect to confirm in the ADR). Same tag as TestImages and
     * compose/compose.yaml.
     */
    public static final String KEYCLOAK = "quay.io/keycloak/keycloak:26.8.0";

    private TenancyTestImages() {
    }
}
