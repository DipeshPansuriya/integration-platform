package io.integrationplatform.e2e;

/**
 * The container images of the E15 end-to-end harness, pinned by tag AND digest (R05). compose/compose.yaml's
 * {@code e2e} profile names the same strings ({@link ComposeE2eProfileTest} keeps them equal).
 */
public final class E2eImages {

    /** apache/activemq-artemis 2.44.0, the multi-arch index digest (Docker Hub API, 2025-11-03). */
    public static final String ARTEMIS =
            "apache/activemq-artemis:2.44.0@sha256:0305c26f19eda00c0506d0953cfe265600a72ac1b17f7934412120c01a595cff";

    /** wiremock/wiremock 3.13.1, the multi-arch index digest - the pin platform-config's WireMockDestination uses. */
    public static final String WIREMOCK =
            "wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0";

    private E2eImages() {
    }
}
