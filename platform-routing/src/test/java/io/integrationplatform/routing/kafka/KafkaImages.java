package io.integrationplatform.routing.kafka;

/**
 * The one place an image tag is named for the E8 Kafka tests (R05: every image pinned to an exact version or
 * digest). compose/compose.yaml uses the same references; PinnedImagesAndNoLiteralPasswordsTest (platform-app)
 * re-checks the pin rules for the compose text and every Java image constant.
 */
public final class KafkaImages {

    /**
     * Apache Kafka 4.3.1 - the same version line as the pinned kafka-clients 4.3.1 (R12); single KRaft broker.
     * Tag verified against Docker Hub (apache/kafka:4.3.1 pushed 2026-06-17 by apache ASF CI).
     */
    public static final String KAFKA = "apache/kafka:4.3.1";

    /**
     * Kafbat UI release 1.5.0 pinned by its registry digest - only stable release tag on Docker Hub is
     * "v1.5.0", which R05's pinned-tag pattern cannot carry, so the tag's mult-arch index digest of 2026-10-12
     * pins it instead (PinnedImagesAndNoLiteralPasswordsTest accepts an @sha256 digest).
     */
    public static final String KAFBAT = "kafbat/kafka-ui@sha256:7cda86a33344160309fdb65146332e4da65db81a945614f2fe32e210803f6fd1";

    private KafkaImages() {
    }
}