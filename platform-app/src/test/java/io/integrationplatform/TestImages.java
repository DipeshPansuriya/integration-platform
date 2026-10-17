package io.integrationplatform;

/**
 * The one place a Testcontainers image is named (R05: every tag pinned to an exact version).
 * compose/compose.yaml uses the same tag; PinnedImagesAndNoLiteralPasswordsTest checks both.
 */
public final class TestImages {

    /** PostgreSQL 17.11 on Alpine; verified with docker manifest inspect and docker pull (index digest sha256:b0f9560a2de0...). */
    public static final String POSTGRES = "postgres:17.11-alpine";

    /** Keycloak 26.8.0 (E4, T-097): the newest stable tag docker pull confirmed on 2026-10-07; compose uses the same. */
    public static final String KEYCLOAK = "quay.io/keycloak/keycloak:26.8.0";

    /** Apache Kafka 4.3.1 (E11 T-132): the same tag platform-routing's KafkaImages pins, matching kafka-clients 4.3.1. */
    public static final String KAFKA = "apache/kafka:4.3.1";

    private TestImages() {
    }
}
