package io.integrationplatform.config.mapping.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.application.FlowBundle.Parsed;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotMapping;
import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * The publish-time pin check (T-190, ADR-011 Decision 3 rule 6, ADR-013 Decision 5): before an approved flow
 * version publishes, every route's mapped pins must still match what the version pinned at draft save - the
 * source contract version the interface version pins, and the target contract version APPROVED,
 * TARGET_REQUEST and of the system the route delivers to. Any drift or mismatch refuses THEpublish (409,
 * transaction rolls back, nothing published) and names the route and the versions in disagreement.
 *
 * <p>EXACTLY ONE call site: {@code FlowAdminService#publish} after the bundle parse. Snapshot rows whose
 * bindings were null (the '$' default of a fresh route) are refused: publishing an unbound route is not
 * possible under the pin rule (ADR-011 rule 6 with ADR-004 rule 3.</p>
 *
 * <p>T-037 (E7-C5, ADR-013 Decision 6): the pinned mapping content must also have a PASSING sample run of exactly
 * that content hash ({@link MappingSampleRuns#lastPassing}); a mapping never sample-run, changed since its last
 * passing run, or whose run did not pass refuses the publish and asks for the sample run.</p>
 */
public class MappingPublishPinCheck {

    private final JdbcMappingRepository mappings;
    private final MappingSampleRuns sampleRuns;

    public MappingPublishPinCheck(JdbcMappingRepository mappings, MappingSampleRuns sampleRuns) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.sampleRuns = Objects.requireNonNull(sampleRuns, "sampleRuns");
    }

    @Transactional(readOnly = true)
    public void verifyPublishPins(String flowId,
            io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo approved,
            Parsed bundle) {
        Map<String, SnapshotMapping> byRoute = new HashMap<>();
        for (SnapshotMapping mapping : bundle.mappings()) {
            byRoute.put(mapping.routeId(), mapping);
        }
        for (var route : bundle.routes()) {
            SnapshotMapping mapping = byRoute.get(route.id());
            if (mapping == null || mapping.mappingVersionId() == null) {
                throw ConfigApiException.conflict("publish refused on flow " + flowId + " route " + route.id()
                        + ": its mapping is not bound to contract versions - bind one source and one target "
                        + "contract version to the mapping before publish (ADR-011 rule 6)");
            }
            JdbcMappingRepository.VersionFacts version =
                    mappings.findVersionById(mapping.mappingVersionId())
                            .orElseThrow(() -> ConfigApiException.conflict("publish refused on flow " + flowId
                                    + " route " + route.id() + ": pinned mapping version "
                                    + mapping.mappingVersionId() + " is not visible"));
            verifyContent(flowId, route.id(), mapping, version);
            verifySourcePin(flowId, route.id(), version, approved);
            verifyTargetPin(flowId, route, version);
            verifySampleRun(flowId, route.id(), version);
        }
    }

    /**
     * T-037: the pinned content's last PASSING sample run must exist - exactly this content hash, nothing older. The
     * run is keyed by the pinned version row's OWN mapping (mapping_version's (mapping_id, content_sha256) identity,
     * ADR-013), never by the id the snapshot names: the version row is the content authority.
     */
    private void verifySampleRun(String flowId, String routeId, JdbcMappingRepository.VersionFacts version) {
        if (sampleRuns.lastPassing(UUID.fromString(version.mappingId()),
                HexFormat.of().formatHex(version.contentSha256())).isEmpty()) {
            throw ConfigApiException.conflict("publish refused on flow " + flowId + " route " + routeId
                    + ": its mapping " + version.mappingId() + " has no passing sample run of its pinned content"
                    + " (mapping version " + version.id() + ") - run the sample run (POST /api/v1/admin/mappings/"
                    + version.mappingId() + "/test) and publish again (T-037)");
        }
    }

    /** ADR-013 Decision 3: the referenced version row is exactly the snapshot's content, hash verified. */
    private void verifyContent(String flowId, String routeId, SnapshotMapping mapping,
            JdbcMappingRepository.VersionFacts version) {
        byte[] computed = MappingContent.contentSha256(version.engine(), version.sourceId(), version.targetId(),
                version.fieldMappingsText(), version.compiledExpression());
        if (!MessageDigest.isEqual(computed, version.contentSha256())
                || !version.sourceId().equals(mapping.sourceContractVersionId())
                || !version.targetId().equals(mapping.targetContractVersionId())
                || !MappingContent.canonicalFieldMappings(version.fieldMappingsText())
                        .equals(MappingContent.canonicalFieldMappings(mapping.fieldMappingsJson()))
                || !version.compiledExpression().equals(mapping.compiledExpression())) {
            throw ConfigApiException.conflict("publish refused on flow " + flowId + " route " + routeId
                    + ": its pinned mapping version " + version.id() + " no longer matches the approved "
                    + "snapshot content");
        }
    }

    /** The source pin: the row's source binding must BE the interface version's pinned source version. */
    private void verifySourcePin(String flowId, String routeId, JdbcMappingRepository.VersionFacts version,
            io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo approved) {
        if (!version.sourceId().equals(approved.sourceContractVersionId())) {
            JdbcMappingRepository.ContractVersionFacts pinned =
                    mappings.findContractVersionFacts(approved.sourceContractVersionId())
                            .orElseThrow(ConfigApiException::notFound);
            JdbcMappingRepository.ContractVersionFacts mapped =
                    mappings.findContractVersionFacts(version.sourceId()).orElseThrow(ConfigApiException::notFound);
            throw ConfigApiException.conflict("publish refused on flow " + flowId + " route " + routeId
                    + ": the mapping's source contract version " + mapped.versionLabel() + " ("
                    + version.sourceId() + ") is not the interface version's pinned source contract "
                    + pinned.versionLabel() + " (" + approved.sourceContractVersionId() + ")");
        }
    }

    /** The target pin: APPROVED + TARGET_REQUEST + the system the route's endpoint delivers to. */
    private void verifyTargetPin(String flowId, io.integrationplatform.config.flow.application.FlowBundle.SnapshotRoute route,
            JdbcMappingRepository.VersionFacts version) {
        String systemId = mappings.findSystemOfEndpoint(route.targetEndpointId())
                .orElseThrow(() -> ConfigApiException.conflict(
                        "publish refused on flow " + flowId + " route " + route.id() + ": its target endpoint "
                        + route.targetEndpointId() + " is not visible"));
        if (!"APPROVED".equals(version.targetStatus()) || !"TARGET_REQUEST".equals(version.targetRole())
                || !systemId.equals(version.targetSystemId())) {
            String expected = mappings.findApprovedTargetsOfSystem(systemId).stream()
                    .map(f -> f.versionLabel() + " (" + f.id() + ")")
                    .collect(Collectors.joining(", "));
            throw ConfigApiException.conflict("publish refused on flow " + flowId + " route " + route.id()
                    + ": its mapping's target contract version (" + version.targetId() + ") is not the "
                    + "approved TARGET_REQUEST contract version of the route's target system - expected one of: "
                    + (expected.isEmpty() ? "none" : expected));
        }
    }
}