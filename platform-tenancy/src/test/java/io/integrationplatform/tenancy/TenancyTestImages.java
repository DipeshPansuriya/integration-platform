package io.integrationplatform.tenancy;

/**
 * E1 seam: the one place platform-tenancy's tests name a Testcontainers image (R05: an exact pinned tag). The same tag
 * as platform-app's TestImages and compose/compose.yaml. Every E1 lane's container test uses this constant.
 */
public final class TenancyTestImages {

    /** PostgreSQL 17.11 on Alpine - the tag platform-app's start proof verified. */
    public static final String POSTGRES = "postgres:17.11-alpine";

    private TenancyTestImages() {
    }
}
