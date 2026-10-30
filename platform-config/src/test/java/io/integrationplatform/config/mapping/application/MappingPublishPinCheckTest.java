package io.integrationplatform.config.mapping.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.application.FlowBundle.Parsed;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotMapping;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotRoute;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository.VersionFacts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row 278 T84 (E7 close, Karan's info note on T-190): the publish pin check must also prove the pinned
 * mapping_version row belongs to the SAME mapping the approved snapshot names. Content identity alone is not
 * enough - a version row of another mapping of the same tenant with identical content would otherwise pass.
 */
class MappingPublishPinCheckTest {

    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String ROUTE = "33333333-3333-3333-3333-33333333333a";
    private static final String MAPPING_X = "44444444-4444-4444-4444-44444444444a";
    private static final String MAPPING_Y = "44444444-4444-4444-4444-44444444444b";
    private static final String VERSION_OF_Y = "66666666-6666-6666-6666-66666666666b";
    private static final String SOURCE = "77777777-7777-7777-7777-777777777771";
    private static final String TARGET = "77777777-7777-7777-7777-777777777772";
    private static final String ENDPOINT = "55555555-5555-5555-5555-55555555555a";
    private static final String SYSTEM = "88888888-8888-8888-8888-888888888888";
    private static final String ENGINE = "JSONATA";
    private static final String FIELDS = "[{\"source\":\"customerId\",\"target\":\"customer_code\"}]";
    private static final String EXPRESSION = "$.bound";

    private final JdbcMappingRepository mappings = mock(JdbcMappingRepository.class);
    private final MappingSampleRuns sampleRuns = mock(MappingSampleRuns.class);
    private final MappingPublishPinCheck check = new MappingPublishPinCheck(mappings, sampleRuns);

    private final VersionInfo approved = new VersionInfo("v2", 2, "APPROVED", "$.orderId", "{}", SOURCE, "SRC");

    private Parsed snapshotNaming(String mappingId) {
        return new Parsed(List.of(new SnapshotRoute(ROUTE, 1, null, true, "ACTIVE", ENDPOINT, null, null)),
                List.of(new SnapshotMapping(mappingId, ROUTE, FIELDS, EXPRESSION, SOURCE, TARGET, VERSION_OF_Y)));
    }

    /** A version row of mapping Y with exactly the snapshot's content; every other pin and the sample run pass. */
    private void pinnedVersionBelongsTo(String mappingId) {
        VersionFacts version = new VersionFacts(VERSION_OF_Y, 1, ENGINE, SOURCE, TARGET, FIELDS, EXPRESSION,
                MappingContent.contentSha256(ENGINE, SOURCE, TARGET, FIELDS, EXPRESSION), "APPROVED",
                "TARGET_REQUEST", SYSTEM, mappingId);
        when(mappings.findVersionById(VERSION_OF_Y)).thenReturn(Optional.of(version));
        when(mappings.findSystemOfEndpoint(ENDPOINT)).thenReturn(Optional.of(SYSTEM));
        when(sampleRuns.lastPassing(any(), any())).thenReturn(Optional.of(Instant.EPOCH));
    }

    @Test
    @DisplayName("A pinned version of another mapping with identical content is refused 409, naming flow, route and both mapping ids")
    void pinnedVersionOfAnotherMappingIsRefused() {
        pinnedVersionBelongsTo(MAPPING_Y);

        assertThatThrownBy(() -> check.verifyPublishPins(FLOW, approved, snapshotNaming(MAPPING_X)))
                .isInstanceOf(ConfigApiException.class)
                .satisfies(e -> {
                    ConfigApiException refused = (ConfigApiException) e;
                    assertThat(refused.httpStatus()).isEqualTo(409);
                    // the 409 response body's "error" text is what the caller reads
                    assertThat(String.valueOf(refused.body().get("error")))
                            .contains(FLOW).contains(ROUTE).contains(MAPPING_X).contains(MAPPING_Y);
                });
    }

    @Test
    @DisplayName("The pinned version of the snapshot's own mapping still passes")
    void pinnedVersionOfTheSameMappingPasses() {
        pinnedVersionBelongsTo(MAPPING_X);

        assertThatCode(() -> check.verifyPublishPins(FLOW, approved, snapshotNaming(MAPPING_X)))
                .doesNotThrowAnyException();
    }
}
