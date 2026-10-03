package io.integrationplatform;

/**
 * The one place a Testcontainers image is named (R05: every tag pinned to an exact version).
 * compose/compose.yaml uses the same tag; PinnedImagesAndNoLiteralPasswordsTest checks both.
 */
public final class TestImages {

    /** PostgreSQL 17.11 on Alpine; verified with docker manifest inspect and docker pull (index digest sha256:b0f9560a2de0...). */
    public static final String POSTGRES = "postgres:17.11-alpine";

    private TestImages() {
    }
}
