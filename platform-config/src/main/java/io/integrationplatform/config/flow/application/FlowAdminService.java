package io.integrationplatform.config.flow.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.flow.api.InterfacePublished;
import io.integrationplatform.config.flow.api.MaskedFields;
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
import io.integrationplatform.domain.audit.ConfigAuditActions;
import io.integrationplatform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
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
    private static final String FILE_PATTERN_REFUSAL = "FILE_NAME_PATTERN_NEEDS_DELIVERY_ID";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FlowAdminService.class);

    private final JdbcFlowRepository flows;
    private final SystemAdminService systems;
    private final ContractAdminService contracts;
    private final io.integrationplatform.config.mapping.application.MappingPublishPinCheck mappingPins;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;

    /**
     * @param events  T-116: where a successful publish announces {@link InterfacePublished} (the routing module
     *                instantiates the interface's route instances from it)
     * @param tenants the bound tenant the announcement carries (fails closed when none is bound)
     */
    public FlowAdminService(JdbcFlowRepository flows, SystemAdminService systems, ContractAdminService contracts,
            io.integrationplatform.config.mapping.application.MappingPublishPinCheck mappingPins,
            ApplicationEventPublisher events, TenantContext tenants) {
        this.flows = Objects.requireNonNull(flows, "flows");
        this.systems = Objects.requireNonNull(systems, "systems");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.mappingPins = Objects.requireNonNull(mappingPins, "mappingPins");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    // ------------------------------------------------------------------ T-015: create and read

    @Transactional
    public InterfaceRow create(JsonNode body, UUID actorId) {
        String businessKeyRule = businessKeyRule(body);
        String sourceSystem = SystemAdminService.required(body, "sourceSystem");
        List<String> destinations = destinations(body);
        String ordering = ordering(body, "PER_KEY");
        Optional<List<String>> maskedFields = maskedFields(body);
        if (!systems.systemExists(sourceSystem)) {
            throw ConfigApiException.validation("unknown source system " + sourceSystem);
        }
        List<String> endpointIds = resolveDestinations(destinations);

        String code = typedCode(body, "flow-" + UUID.randomUUID()); // B26: the typed code is what intake resolves
        String name = nameFromBody(body, code); // F-1: the typed interface name is born on the flow; no name -> the code
        String interfaceId;
        try {
            interfaceId = flows.insertInterface(code, name, ordering);
        } catch (DuplicateKeyException clash) {
            // F17: the unique key is (tenant, code) (V2_2_3), and (tenant, lower(code)) since V2_4_1480 (row 542 F1)
            throw ConfigApiException.conflict("an interface with the code '" + code
                    + "' already exists in this tenant (codes are compared ignoring case)");
        }
        InterfaceRow created = new InterfaceRow(interfaceId, code, name, ordering, "DRAFT");
        maskedFields.ifPresent(paths -> flows.updateMaskedFields(interfaceId, MaskedFields.toJson(paths)));

        String sourceContractVersionId = contracts.getOrCreateSourceDraftVersion(sourceSystem, actorId);
        alignRoutes(interfaceId, endpointIds);
        String versionId = insertSnapshotVersion(created, businessKeyRule, sourceContractVersionId, 1, actorId);
        flows.updateInterfaceAfterDraft(interfaceId, 1);
        audit(ConfigAuditActions.FLOW_CREATED, actorId, interfaceId, versionId, null,
                ConfigAuditActions.state("status", "DRAFT", "version", 1));
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
        Optional<List<String>> maskedFields = maskedFields(body);
        if (!systems.systemExists(sourceSystem)) {
            throw ConfigApiException.validation("unknown source system " + sourceSystem);
        }
        List<String> endpointIds = resolveDestinations(destinations);
        if (!ordering.equals(stored.ordering())) {
            flows.updateInterfaceOrdering(stored.id(), ordering);
        }
        maskedFields.ifPresent(paths -> flows.updateMaskedFields(stored.id(), MaskedFields.toJson(paths)));
        InterfaceRow current = new InterfaceRow(stored.id(), stored.code(), stored.name(), ordering,
                stored.status());

        alignRoutes(current.id(), endpointIds);
        VersionInfo latest = flows.findLatestVersion(current.id())
                .orElseThrow(() -> new IllegalStateException("a flow always has at least one version"));
        int nextVersion = latest.version() + 1;
        String versionId = insertSnapshotVersion(current, businessKeyRule, latest.sourceContractVersionId(),
                nextVersion, actorId);
        flows.updateInterfaceAfterDraft(current.id(), nextVersion);
        audit(ConfigAuditActions.FLOW_DRAFT_SAVED, actorId, current.id(), versionId,
                ConfigAuditActions.state("version", latest.version()),
                ConfigAuditActions.state("version", nextVersion));
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
                "maskedFields", MaskedFields.fromJson(flows.findMaskedFields(flowId).orElse(null)),
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
        if (!isCanonicalUuid(versionId)) {
            throw ConfigApiException.notFound();
        }
        Optional<VersionInfo> version = flows.findVersionOfInterface(flowId, versionId);
        if (version.isEmpty()) {
            throw ConfigApiException.notFound();
        }
        throw ConfigApiException.conflict(
                "version content is immutable: saving a new draft version on the flow is the only way to change it");
    }

    // ------------------------------------------------------------------ T-019: submit / approve

    @Transactional
    public Map<String, Object> submit(String flowId, JsonNode body, UUID actorId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        String changeNote = SystemAdminService.required(body, "changeNote");
        VersionInfo draft = flows.findLatestVersionByStatus(flowId, "DRAFT")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no draft version to submit"));
        if (flows.submitVersion(draft.id(), changeNote) != 1) {
            throw ConfigApiException.conflict("the draft version changed under the submit");
        }
        audit(ConfigAuditActions.FLOW_SUBMITTED, actorId, interfaceRow.id(), draft.id(),
                ConfigAuditActions.state("status", "DRAFT"), ConfigAuditActions.state("status", "PENDING_APPROVAL"));
        return ConfigApiException.body("id", interfaceRow.id(), "version", draft.version());
    }

    /** The approve call's answer: status plus body, designed (not thrown) so the transaction commits. */
    public record ApprovalOutcome(int httpStatus, Map<String, Object> body) {
    }

    /**
     * T-019: approve runs the V2_2_4 definer. A refusal must keep the audit row the function wrote in this same
     * transaction - so the refusal is RETURNED, never thrown: a thrown exception would roll the transaction back
     * together with {@code config_version_refusal}, and the scenario demands the refusal is recorded.
     *
     * <p>V5 (Varun, row 372): the FILE-pattern refusal (T178c-1) is the one refusal that is deliberately NOT written to
     * {@code config_version_refusal}. That table records the decision refusals of the approval function only: its
     * CHECK allows AUTHOR_IS_APPROVER (no longer written since V2_4_1420: the author may approve) and
     * NOT_PENDING_APPROVAL (V2_2_4__config_interface_versions.sql:49) and the
     * application role may only read it, the insert belongs to {@code config_approve_version} (same file, :136-141,
     * :160-162). The FILE refusal is a pre-check on the destinations, made before the function is called; nothing about
     * the version changes, and it is logged (count only) at {@link #fileDestinationsWithoutDeliveryId}. Recording it
     * would need a new refusal value and a definer path: a migration, decided separately.
     */
    @Transactional
    public ApprovalOutcome approve(String flowId, UUID approverId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        VersionInfo pending = flows.findLatestVersionByStatus(flowId, "PENDING_APPROVAL")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no version pending approval"));
        if (fileDestinationsWithoutDeliveryId(pending.snapshotJson()) > 0) {
            return new ApprovalOutcome(409, ConfigApiException.refusal(409, FILE_PATTERN_REFUSAL).body());
        }
        String result = flows.approveVersion(pending.id(), approverId.toString());
        return switch (result) {
            case "APPROVED" -> {
                // single-user workflow: the trail says outright when the approver is the author
                boolean selfApproved = approverId.toString().equals(flows.authorOf(pending.id()));
                audit(ConfigAuditActions.FLOW_APPROVED, approverId, interfaceRow.id(), pending.id(),
                        ConfigAuditActions.state("status", "PENDING_APPROVAL"),
                        ConfigAuditActions.state("status", "APPROVED", "selfApproved", selfApproved));
                yield new ApprovalOutcome(200,
                        ConfigApiException.body("id", interfaceRow.id(), "version", pending.version()));
            }
            case "VERSION_NOT_FOUND" -> new ApprovalOutcome(404, ConfigApiException.notFound().body());
            // row 544: a stamping bug, not a business refusal - never mislabelled NOT_PENDING_APPROVAL
            case "REFUSED_NO_ACTOR", "REFUSED_ACTOR_MISMATCH" -> throw new IllegalStateException(
                    "the approval actor was not stamped consistently: " + result);
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
    public Map<String, Object> publish(String flowId, UUID actorId) {
        Published published = materialize(flowId);
        audit(ConfigAuditActions.FLOW_PUBLISHED, actorId, published.interfaceId(), published.version().id(),
                ConfigAuditActions.state("status", "APPROVED"),
                ConfigAuditActions.state("status", "PUBLISHED", "version", published.version().version()));
        return ConfigApiException.body("id", published.interfaceId(), "version", published.version().version());
    }

    /**
     * The same publish for an in-process caller with no human actor (kept for the platform-routing proof): audited as
     * {@link ConfigAuditActions#SYSTEM_ACTOR}, so no publish path skips the audit.
     */
    @Transactional
    public Map<String, Object> publish(String flowId) {
        return publish(flowId, ConfigAuditActions.SYSTEM_ACTOR);
    }

    private record Published(String interfaceId, VersionInfo version) {
    }

    private Published materialize(String flowId) {
        InterfaceRow interfaceRow = requireInterface(flowId);
        VersionInfo approved = flows.findLatestVersionByStatus(flowId, "APPROVED")
                .orElseThrow(() -> ConfigApiException.conflict("the flow has no approved version to publish"));
        if (fileDestinationsWithoutDeliveryId(approved.snapshotJson()) > 0) {
            throw ConfigApiException.refusal(409, FILE_PATTERN_REFUSAL);
        }
        Parsed bundle = FlowBundle.parse(approved.snapshotJson());

        // T-190, ADR-013 Decision 5: the mapped pins of every route are verified against the contract
        // versions the approved version pinned - ONE call site, the only E7 edit to this file.
        mappingPins.verifyPublishPins(interfaceRow.id(), approved, bundle);

        flows.archivePublishedVersions(interfaceRow.id(), approved.id());
        for (SnapshotRoute route : bundle.routes()) {
            if (flows.routeExists(route.id())) {
                flows.updateRouteFromSnapshot(route.id(), route.sequence(), route.condition(),
                        route.isPrimary(), route.status(), route.callbackMode(), route.callbackUrl());
            } else {
                flows.insertRouteFromSnapshot(route.id(), interfaceRow.id(), route.targetEndpointId(),
                        route.sequence(), route.condition(), route.isPrimary(), route.status(),
                        route.callbackMode(), route.callbackUrl());
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
        // T-116: the routing module validates these rows before the commit - it validates; never builds; routes
        // are built lazily in stage 2 (ADR-010 s3 amendment, F16 verdict c). A row it refuses rolls this publish back.
        events.publishEvent(new InterfacePublished(tenants.requireTenantId(), UUID.fromString(interfaceRow.id()),
                approved.version()));
        return new Published(interfaceRow.id(), approved);
    }

    // ------------------------------------------------------------------ IB08: route callback configuration

    private static final List<String> CALLBACK_MODES = List.of("IMMEDIATE", "SCHEDULED");

    @Transactional
    public Map<String, Object> configureRouteCallback(String flowId, String routeId, JsonNode body) {
        requireInterface(flowId);
        if (!isCanonicalUuid(routeId)) {
            throw ConfigApiException.notFound();
        }
        String callbackMode = null;
        String callbackUrl = null;
        JsonNode modeNode = body == null ? null : body.path("callbackMode");
        if (modeNode != null && !modeNode.isMissingNode() && !modeNode.isNull()) {
            callbackMode = modeNode.asString().toUpperCase();
            if (!CALLBACK_MODES.contains(callbackMode)) {
                throw ConfigApiException.validation("callbackMode must be one of " + CALLBACK_MODES + " or null");
            }
            if ("SCHEDULED".equals(callbackMode)) {
                JsonNode urlNode = body.path("callbackUrl");
                if (urlNode == null || urlNode.isMissingNode() || urlNode.isNull()
                        || urlNode.asString().isBlank()) {
                    throw ConfigApiException.validation(
                            "callbackUrl is required when callbackMode is SCHEDULED");
                }
                callbackUrl = urlNode.asString().strip();
                try {
                    new java.net.URI(callbackUrl);
                } catch (java.net.URISyntaxException malformed) {
                    throw ConfigApiException.validation("callbackUrl is not a valid URI");
                }
            }
        }
        int updated = flows.updateRouteCallback(routeId, callbackMode, callbackUrl);
        if (updated == 0) {
            throw ConfigApiException.notFound();
        }
        return ConfigApiException.body("routeId", routeId, "callbackMode", callbackMode,
                "callbackUrl", callbackUrl);
    }

    // ------------------------------------------------------------------ shared steps

    /**
     * T178c-1: stored FILE destinations saved before the {@code {deliveryId}} rule are never rewritten; a flow that
     * still sends to one is not approved or published until an operator edits the pattern. Logs the count only.
     *
     * <p>V6 (Varun, row 372): the destinations are those of the version SNAPSHOT being approved or published (its
     * ACTIVE routes' target endpoints), not the interface's current route rows, which may belong to another version.
     * An INACTIVE route is skipped: routing builds ACTIVE routes only ({@code JdbcRouteConfigReader}, status =
     * 'ACTIVE'). The old read, {@code READ_DESTINATIONS}, had no status filter and counted inactive rows too.
     */
    private long fileDestinationsWithoutDeliveryId(String snapshotJson) {
        List<String> endpointIds = FlowBundle.parse(snapshotJson).routes().stream()
                .filter(route -> "ACTIVE".equalsIgnoreCase(route.status()))
                .map(SnapshotRoute::targetEndpointId).filter(id -> id != null && CANONICAL_UUID.matcher(id).matches())
                .distinct().toList();
        long count = systems.countFileDestinationsWithoutDeliveryId(
                flows.findDestinationsOfEndpoints(endpointIds).stream().map(DestinationRow::systemCode).toList());
        if (count > 0) {
            LOG.warn("flow approval or publish refused: {} FILE destination(s) without {deliveryId} in the file name pattern",
                    count);
        }
        return count;
    }

    /** E12-F2-S1: one audit event per completed lifecycle step, inside its transaction (the writer is AFTER_COMMIT). */
    private void audit(String action, UUID actorId, String interfaceId, String versionId, String before,
            String after) {
        events.publishEvent(ConfigAuditActions.event(action, tenants.requireTenantId(), actorId,
                List.of(ConfigAuditActions.target("interface", interfaceId),
                        ConfigAuditActions.target("interface_version", versionId)), before, after));
    }

    private static final java.util.regex.Pattern CANONICAL_UUID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * T197 R3: a path id that is not a canonical UUID names no row. The repository casts the text with
     * {@code ::uuid}; unguarded, a non-UUID was a PostgreSQL 22P02 and the catch-all 500. The shape is checked, not
     * parsed: {@code UUID.fromString} also accepts forms such as {@code 1-1-1-1-1} that the cast rejects.
     */
    static boolean isCanonicalUuid(String id) {
        return id != null && CANONICAL_UUID.matcher(id).matches();
    }

    /** The one guard in front of every flow path id: every public method starts here, before any SQL. */
    private InterfaceRow requireInterface(String flowId) {
        if (!isCanonicalUuid(flowId)) {
            throw ConfigApiException.notFound();
        }
        return flows.findInterface(flowId).orElseThrow(ConfigApiException::notFound);
    }

    /** F-1: the create body's optional name - used when present and non-blank, otherwise the generated code. */
    private static String nameFromBody(JsonNode body, String fallback) {
        JsonNode node = body == null ? null : body.path("name");
        String text = node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
        // A blank or whitespace-only name carries nothing a configurator typed: fall back, don't store "".
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    /**
     * What a typed interface code may be (row 482, review F2). The code is the intake URL segment
     * ({@code /api/v1/interfaces/{code}/messages}) and is never updatable, so it must be addressable: ASCII
     * letters and digits, then letters, digits, {@code . _ -}; 1 to 63 characters. The strictest reasonable
     * pattern - no business rule beyond "usable in a URL"; loosen it only on a stated need.
     */
    static final java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");

    /**
     * The typed interface code, or the mint when the body names none (B26). Absent, null and blank mint; anything
     * typed must be a JSON string matching {@link #CODE_PATTERN} (400 naming the field). Uniqueness per tenant is
     * the database's (V2_2_3) and answers a named 409 in {@link #create}.
     */
    private static String typedCode(JsonNode body, String fallback) {
        JsonNode node = body == null ? null : body.path("code");
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isString()) {
            throw ConfigApiException.validation("the interface code must be a string: code");
        }
        String text = node.asString().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        if (!CODE_PATTERN.matcher(text).matches()) {
            throw ConfigApiException.validation("the interface code is not usable in an address: code must be 1 to 63 "
                    + "characters, letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
        return text;
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
                            route.status(), route.callbackMode(), route.callbackUrl());
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
                interfaceRow.ordering(), businessKeyRule,
                MaskedFields.fromJson(flows.findMaskedFields(interfaceRow.id()).orElse(null)), routes, mappings);
        return flows.insertVersion(interfaceRow.id(), version, businessKeyRule, snapshot,
                sourceContractVersionId, actorId.toString());
    }

    /** T108: the optional masked-field list of a create/update body; a malformed one is a 400. */
    private static Optional<List<String>> maskedFields(JsonNode body) {
        try {
            return MaskedFields.parse(body);
        } catch (IllegalArgumentException invalid) {
            throw ConfigApiException.validation(invalid.getMessage());
        }
    }
}
