package io.integrationplatform.ingest.internal.outbox;

/**
 * T-007/T-010 lane A: the one place platform-ingest's tests name a Testcontainers image (R05: an exact pinned tag).
 * The same tag as platform-tenancy's TenancyTestImages, platform-app's TestImages and compose/compose.yaml.
 */
public final class IngestTestImages {

    /** PostgreSQL 17.11 on Alpine - the tag platform-app's start proof verified. */
    public static final String POSTGRES = "postgres:17.11-alpine";

    private IngestTestImages() {
    }
}
