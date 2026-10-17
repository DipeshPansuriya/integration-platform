package io.integrationplatform.config.mapping.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The mapping save and read (T-190): {@code GET/PUT /api/v1/admin/routes/{routeId}/mapping}. The PUT is the
 * content-change moment: it validates the bindings against the bound contracts (one source + one target,
 * links pointing only at fields the target contract has - named in the refusal), writes the live row and
 * mints exactly one mapping_version row per NEW content, deduplicated by content hash (ADR-013 Decision 2).
 * The GET re-renders the stored row exactly as it was left, so the UI round-trips the saved state.
 */
public class MappingAdminService {

    private final JdbcMappingRepository mappings;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;

    /**
     * @param events  where a completed save publishes its audit event (E12-F2-S1)
     * @param tenants the bound tenant the audit event names
     */
    public MappingAdminService(JdbcMappingRepository mappings, ApplicationEventPublisher events,
            TenantContext tenants) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> read(String routeId) {
        JdbcMappingRepository.LiveMapping live =
                mappings.findMappingOfRoute(routeId).orElseThrow(ConfigApiException::notFound);
        return ConfigApiException.body(
                "routeId", routeId,
                "mappingId", live.id(),
                "fieldMappings", tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(MappingContent.canonicalFieldMappings(live.fieldMappingsJson())),
                "compiledExpression", live.compiledExpression(),
                "sourceContractVersionId", live.sourceContractVersionId(),
                "targetContractVersionId", live.targetContractVersionId(),
                "mappingVersionId", live.mappingVersionId());
    }

    @Transactional
    public java.util.Map<String, Object> save(String routeId, JsonNode body, java.util.UUID actorId) {
        JdbcMappingRepository.LiveMapping live =
                mappings.findMappingOfRoute(routeId).orElseThrow(ConfigApiException::notFound);

        JsonNode linksNode = body.path("fieldMappings");
        String fieldMappingsJson = MappingContent.canonicalFieldMappings(
                linksNode.isMissingNode() ? "[]" : linksNode.toString());
        String compiledExpression = requiredText(body, "compiledExpression");
        String source = textOrNull(body.path("sourceContractVersionId"));
        String target = textOrNull(body.path("targetContractVersionId"));
        boolean bound = source != null || target != null;

        if (bound && (source == null || target == null)) {
            throw ConfigApiException.validation(
                    "a mapping binds one source and one target contract version together (ADR-004 rule 3)");
        }
        if (!fieldMappingsJson.equals("[]") && !bound) {
            throw ConfigApiException.validation(
                    "field links require the mapping's bound source and target contract versions");
        }
        if (target != null) {
            validateBindings(routeId, fieldMappingsJson, source, target);
        }

        mappings.updateMappingOfRoute(routeId, fieldMappingsJson, compiledExpression, source, target);

        String versionId = null;
        int version = 0;
        if (target != null) {
            byte[] content = MappingContent.contentSha256(MappingContent.ENGINE, source, target,
                    fieldMappingsJson, compiledExpression);
            JdbcMappingRepository.LiveMappingVersionRef existing =
                    mappings.findVersionOfContent(live.id(), source, target, fieldMappingsJson,
                            compiledExpression).orElse(null);
            if (existing == null) {
                mappings.insertVersion(live.id(), mappings.nextVersionOfMapping(live.id()), source, target,
                        MappingContent.ENGINE, fieldMappingsJson, compiledExpression, content,
                        actorId.toString());
                existing = mappings.findVersionOfContent(live.id(), source, target, fieldMappingsJson,
                        compiledExpression).orElseThrow(() -> new IllegalStateException(
                        "the just-saved mapping version is not visible to its own transaction"));
            }
            versionId = existing.id();
            version = existing.version();
        }
        return ConfigApiException.body(
                "routeId", routeId,
                "mappingId", live.id(),
                "fieldMappings", tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(fieldMappingsJson),
                "compiledExpression", compiledExpression,
                "sourceContractVersionId", source,
                "targetContractVersionId", target,
                "mappingVersionId", versionId,
                "mappingVersion", version == 0 ? null : version);
    }

    private void validateBindings(String routeId, String fieldMappingsJson, String source, String target) {
        JdbcMappingRepository.ContractVersionFacts sourceFacts =
                mappings.findContractVersionFacts(source).orElseThrow(ConfigApiException::notFound);
        JdbcMappingRepository.ContractVersionFacts targetFacts =
                mappings.findContractVersionFacts(target).orElseThrow(ConfigApiException::notFound);
        if (!"SOURCE_REQUEST".equals(sourceFacts.role())) {
            // The source binding may still be the drafting (unapproved) source version - the publish pin
            // check compares it against the version the interface pinned and is the authority there.
            throw ConfigApiException.conflict("the source contract version "
                    + sourceFacts.versionLabel() + " of " + sourceFacts.systemCode()
                    + " is not a SOURCE_REQUEST contract version");
        }
        if (!"TARGET_REQUEST".equals(targetFacts.role()) || !"APPROVED".equals(targetFacts.status())) {
            throw ConfigApiException.conflict("the target contract version "
                    + targetFacts.versionLabel() + " of " + targetFacts.systemCode()
                    + " is not an approved TARGET_REQUEST contract version");
        }
        String schema = mappings.findSchemaOfVersion(target).orElseThrow(ConfigApiException::notFound);
        for (String root : MappingContent.targetRoots(fieldMappingsJson)) {
            if (!tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(schema).path("properties").has(root)) {
                throw ConfigApiException.validation("the target contract "
                        + targetFacts.versionLabel() + " of " + targetFacts.systemCode()
                        + " does not have field " + root);
            }
        }
    }

    private static String requiredText(JsonNode body, String field) {
        String text = textOrNull(body.path(field));
        if (text == null || text.isBlank()) {
            throw ConfigApiException.validation("the mandatory " + field + " is missing");
        }
        return text;
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}