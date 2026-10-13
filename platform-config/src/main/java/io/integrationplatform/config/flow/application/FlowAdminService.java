package io.integrationplatform.config.flow.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.flow.application.FlowBundle.Parsed;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotMapping;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotRoute;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.DestinationRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The flow and its lifecycle (T-015, T-019, T-021). Every save is a new {@code DRAFT} version row carrying the
 * canonical snapshot read back from the live rows; submit walks that draft to pending approval (its change note
 * stored); approve runs the V2_2_4 definer (the author-never-approver refusal is the database's, recorded there,
 * translated to the named 403 here); publish materializes the approved snapshot over the live rows - interface,
 * routes and mappings in ONE transaction, so a failed publish leaves the previous version's bundle exactly live
 * (T-021). Rules live here; the controller seam is HTTP-only.
 */
public class FlowAdminService {

    private static final List<String> ORDERINGS = List.of("PER_KEY", "NONE");
    private static final String DEFAULT_EXPRESSION = "$";

    private final JdbcFlowRepository flows;
    private final SystemAdminService systems;
    private final ContractAdminService contracts;
    private final io.integrationplatform.config.mapping.application.MappingPublishPinCheck mappingPins;

    public FlowAdminService(JdbcFlowRepository flows, SystemAdminService systems, ContractAdminService contracts,
            io.integrationplatform.config.mapping.application.MappingPublishPinCheck mappingPins) {
        this.flows = Objects.requireNonNull(flows, "flows");
        this.systems = Objects.requireNonNull(systems, "systems");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.mappingPins = Objects.requireNonNull(mappingPins, "mappingPins");
    }

    // ------------------------------------------------------------------ T-015: create and read

    @Transactional
    public InterfaceRow create(JsonNode body, UUID actorId) {
        String businessKeyRule = businessKeyRule(body);
        String sourceSystem = SystemAdminService.required(body, "sourceSystem");
        List<String> destinations = destinations(body);
        String ordering = ordering(body, "PER_KEY");
        if (!systems.systemExists(sourceSystem)) {
            throw ConfigApiException.validation("unknown source system " + sourceSystem);
        }
        List<String> endpointIds = resolveDestinations(destinations);

        String code = "flow-" + UUID.randomUUID();
        String interfaceId = flows.insertInterface(code, code, ordering);
        InterfaceRow created = new InterfaceRow(interfaceId, code, code, ordering, "DRAFT");

        String sourceContractVersionId = contracts.getOrCreateSourceDraftVersion(sourceSystem, actorId);
        alignRoutes(interfaceId, endpointIds);
        insertSnapshotVersion(created, businessKeyRule, sourceContractVersionId, 1, actorId);
        flows.updateInterfaceAfterDraft(interfaceId, 1);
        return created;
    }

    /** PUT /api/v1/admin/flows/{flowId}: the draft-save - a new DRAFT version, the live rows aligned to it. */
    @Transactional
    public Map<String, Object> saveDraft(String flowId, JsonNode body, UUID actorId) {
        InterfaceRow stored = requireInterface(flowId);
        String businessKeyRule = businessKeyRule(body);
        String sourceSystem = SystemAdminService.required(body, "sourceSystem");
        List<String> destinations = destinations(body);
        String ordering = ordering(body, stored.ordering());
        if (!systems.systemExists(sourceSystem)) {
            throw ConfigApiException.validation("unknown source system " + sourceSystem);
        }
        List<String> endpointIds = resolveDestinations(destinations);
        if (!ordering.equals(stored.ordering())) {
            flows.updateInterfaceOrdering(stored.id(), ordering);
        }
        InterfaceRow current = new InterfaceRow(stored.id(), stored.code(), stored.name(), ordering,
                stored.status());

        alignRoutes(current.id(), endpointIds);
        VersionInfo latest = flows.findLatestVersion(current.id())
                .orElseThrow(() -> new IllegalStateException("a flow always has at least one version"));
        int nextVersion = latest.version() + 1;
        insertSnapshotVersion(current, businessKeyRule, latest.sourceContractVersionId(), nextVersion, actorId);
        flows.updateInterfaceAfterDraft(current.id(), nextVersion);
        return ConfigApiException.body("id", current.id(), "version", nextVersion);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(String flowId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        VersionInfo latest = flows.findLatestVersion(flowId)
                .orElseThrow(() -> new IllegalStateException("a flow always has at least one version"));
        List<String> destinationCodes = flows.findDestinations(flowId).stream()
                .sorted(Comparator.comparingInt(DestinationRow::sequence).thenComparing(DestinationRow::systemCode))
                .map(DestinationRow::systemCode)
                .toList();
        return ConfigApiException.body(
                "id", interfaceRow.id(),
                "code", interfaceRow.code(),
                "name", interfaceRow.name(),
                "sourceSystem", latest.sourceSystem(),
                "destinations", destinationCodes,
                "businessKeyRule", latest.businessKeyRule(),
                "ordering", interfaceRow.ordering(),
                "status", interfaceRow.status());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (InterfaceRow row : flows.listInterfaces()) {
            summaries.add(ConfigApiException.body("id", row.id(), "code", row.code(), "name", row.name(),
                    "ordering", row.ordering(), "status", row.status()));
        }
        return summaries;
    }

    /**
     * The version-edit attempt (T-019): content is immutable once a version row exists - even a draft's content
     * changes by saving a new draft through the flow. The refusal names the only way.
     */
    @Transactional(readOnly = true)
    public void refuseVersionEdit(String flowId, String versionId) {
        requireInterface(flowId);
        Optional<VersionInfo> version = flows.findVersionOfInterface(flowId, versionId);
        if (version.isEmpty()) {
            throw ConfigApiException.notFound();
        }
        throw ConfigApiException.conflict(
                "version content is immutable: saving a new draft version on the flow is the only way to change it");
    }

    // ------------------------------------------------------------------ T-019: submit / approve

    @Transactional
    public Map<String, Object> submit(String flowId, JsonNode body) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        String changeNote = SystemAdminService.required(body, "changeNote");
        VersionInfo draft = flows.findLatestVersionByStatus(flowId, "DRAFT")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no draft version to submit"));
        if (flows.submitVersion(draft.id(), changeNote) != 1) {
            throw ConfigApiException.conflict("the draft version changed under the submit");
        }
        return ConfigApiException.body("id", interfaceRow.id(), "version", draft.version());
    }

    /** The approve call's answer: status plus body, designed (not thrown) so the transaction commits. */
    public record ApprovalOutcome(int httpStatus, Map<String, Object> body) {
    }

    /**
     * T-019: approve runs the V2_2_4 definer. A refusal must keep the audit row the function wrote in this same
     * transaction - so the refusal is RETURNED, never thrown: a thrown exception would roll the transaction back
     * together with {@code config_version_refusal}, and the scenario demands the refusal is recorded.
     */
    @Transactional
    public ApprovalOutcome approve(String flowId, UUID approverId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        VersionInfo pending = flows.findLatestVersionByStatus(flowId, "PENDING_APPROVAL")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no version pending approval"));
        String result = flows.approveVersion(pending.id(), approverId.toString());
        return switch (result) {
            case "APPROVED" -> new ApprovalOutcome(200,
                    ConfigApiException.body("id", interfaceRow.id(), "version", pending.version()));
            case "VERSION_NOT_FOUND" -> new ApprovalOutcome(404, ConfigApiException.notFound().body());
            case "REFUSED_AUTHOR_IS_APPROVER" -> new ApprovalOutcome(403,
                    ConfigApiException.refusal(403, "AUTHOR_IS_APPROVER").body());
            default -> new ApprovalOutcome(409, ConfigApiException.refusal(409, "NOT_PENDING_APPROVAL").body());
        };
    }

    // ------------------------------------------------------------------ T-021: the atomic publish

    /**
     * Materializes the approved snapshot over the live rows in one transaction: the superseded published version
     * archived, every snapshot route and mapping written (row kept if it exists, inserted under the snapshot's
     * own id if not - the ids make the live bundle equal the snapshot byte for byte), then the version's status
     * and the interface's pointer. Any write that fails mid-bundle rolls all of it back: the previous version
     * stays published with its bundle exactly live.
     */
    @Transactional
    public Map<String, Object> publish(String flowId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        VersionInfo approved = flows.findLatestVersionByStatus(flowId, "APPROVED")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no approved version to publish"));
        Parsed bundle = FlowBundle.parse(approved.snapshotJson());

        // T-190, ADR-013 Decision 5: the mapped pins of every route are verified against the contract
        // versions the approved version pinned - ONE call site, the only E7 edit to this file.
        mappingPins.verifyPublishPins(interfaceRow.id(), approved, bundle);

        flows.archivePublishedVersions(interfaceRow.id(), approved.id());
        for (SnapshotRoute route : bundle.routes()) {
            if (flows.routeExists(route.id())) {
                flows.updateRouteFromSnapshot(route.id(), route.sequence(), route.condition(),
                        route.isPrimary(), route.status());
            } else {
                flows.insertRouteFromSnapshot(route.id(), interfaceRow.id(), route.targetEndpointId(),
                        route.sequence(), route.condition(), route.isPrimary(), route.status());
            }
        }
        for (SnapshotMapping mapping : bundle.mappings()) {
            if (flows.mappingExists(mapping.id())) {
                flows.updateMappingFromSnapshot(mapping.id(), mapping.fieldMappingsJson(),
                        mapping.compiledExpression(), mapping.sourceContractVersionId(),
                        mapping.targetContractVersionId());
            } else {
                flows.insertMappingFromSnapshot(mapping.id(), mapping.routeId(), mapping.fieldMappingsJson(),
                        mapping.compiledExpression(), mapping.sourceContractVersionId(),
                        mapping.targetContractVersionId());
            }
        }
        if (flows.publishVersion(approved.id()) != 1) {
            throw ConfigApiException.conflict("the approved version changed under the publish");
        }
        flows.updateInterfaceAfterPublish(interfaceRow.id(), approved.version());
        return ConfigApiException.body("id", interfaceRow.id(), "version", approved.version());
    }

    // ------------------------------------------------------------------ shared steps

    private InterfaceRow requireInterface(String flowId) {
        return flows.findInterface(flowId).orElseThrow(ConfigApiException::notFound);
    }

    private static String businessKeyRule(JsonNode body) {
        JsonNode rule = body == null ? null : body.path("businessKeyRule");
        String text = rule == null || rule.isMissingNode() || rule.isNull() ? null : rule.asString();
        if (text == null || text.isBlank()) {
            throw ConfigApiException.validation(
                    "the mandatory business-key rule is missing: businessKeyRule");
        }
        return text;
    }

    private static String ordering(JsonNode body, String defaultOrCurrent) {
        JsonNode node = body == null ? null : body.path("ordering");
        String text = node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
        String candidate = text == null || text.isBlank() ? defaultOrCurrent : text;
        if (!ORDERINGS.contains(candidate)) {
            throw ConfigApiException.validation("ordering must be one of " + ORDERINGS);
        }
        return candidate;
    }

    private static List<String> destinations(JsonNode body) {
        List<String> codes = new ArrayList<>();
        if (body != null) {
            for (JsonNode entry : body.path("destinations")) {
                codes.add(entry.asString());
            }
        }
        if (codes.isEmpty()) {
            throw ConfigApiException.validation("destinations must name at least one registered system");
        }
        return codes;
    }

    /** T-015: naming a destination resolves to its endpoint; an unregistered code is brought on-line with it. */
    private List<String> resolveDestinations(List<String> codes) {
        List<String> endpointIds = new ArrayList<>(codes.size());
        for (String code : codes) {
            endpointIds.add(systems.endpointFor(code));
        }
        return endpointIds;
    }

    /** Aligns the live routes to the destination list: sequence by position, a new row (with its default mapping) per new endpoint. */
    private void alignRoutes(String interfaceId, List<String> endpointIds) {
        for (int position = 0; position < endpointIds.size(); position++) {
            String endpointId = endpointIds.get(position);
            int sequence = position + 1;
            Optional<RouteRow> existing = flows.findRouteByEndpoint(interfaceId, endpointId);
            if (existing.isPresent()) {
                RouteRow route = existing.get();
                if (route.sequence() != sequence) {
                    flows.updateRouteFromSnapshot(route.id(), sequence, route.condition(), route.isPrimary(),
                            route.status());
                }
            } else {
                String routeId = flows.insertRoute(interfaceId, endpointId, sequence);
                flows.insertMapping(routeId, DEFAULT_EXPRESSION);
            }
        }
    }

    /** The snapshot is a read-back of the live rows (the byte-for-byte contract), then stored as the new draft version. */
    private String insertSnapshotVersion(InterfaceRow interfaceRow, String businessKeyRule,
            String sourceContractVersionId, int version, UUID actorId) {
        List<RouteRow> routes = flows.findRoutes(interfaceRow.id());
        List<MappingRow> mappings = flows.findMappings(interfaceRow.id());
        String snapshot = FlowBundle.serialize(interfaceRow.code(), interfaceRow.name(),
                interfaceRow.ordering(), businessKeyRule, routes, mappings);
        return flows.insertVersion(interfaceRow.id(), version, businessKeyRule, snapshot,
                sourceContractVersionId, actorId.toString());
    }
}
