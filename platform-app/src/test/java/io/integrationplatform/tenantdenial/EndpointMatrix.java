package io.integrationplatform.tenantdenial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * The forged-tenant matrix (T-002, release gate): one entry per {@code /api/**} endpoint of the whole application.
 * Every new endpoint of every module adds its entry here (from E5 on: in its lane's {@link LaneMatrix} file); the
 * coverage guard fails the suite when an endpoint has
 * none, when an entry no longer matches an endpoint, or when an entry misstates whether the endpoint takes a body.
 * Which forgeries apply is derived from the entry ({@link Forgery#fits}).
 */
final class EndpointMatrix {

    /**
     * Two seeded tenants seen from one caller: {@code a} is the calling tenant (its user or source signs in),
     * {@code b} is the other one, the tenant every forgery names. The entries only ever speak of {@code a} and
     * {@code b}, so one matrix serves the callers of both tenants ({@link #swapped()}).
     */
    record Seed(SeededTenant a, SeededTenant b) {

        /** The same two tenants seen from tenant B's side. */
        Seed swapped() {
            return new Seed(b, a);
        }
    }

    /**
     * One tenant with its ACTIVE Keycloak user (bound by issuer + subject, holding TENANT_ADMIN), a second user whose
     * token claims the other tenant, its source system's machine credential (an ACTIVE source_credential row for a
     * Keycloak client, with its customer identity), its probe rows and its seeded config chain (labels carry the
     * tenant's word).
     */
    record SeededTenant(UUID id, String code, UUID userId, String subject, UUID claimUserId, UUID machineSystemId,
            String customerId, String clientId, List<UUID> rowIds, List<String> labels, Config config) {

        /** Every value that identifies this tenant's data; none of them may reach the other tenant's callers. */
        List<String> markers() {
            List<String> markers = new ArrayList<>(List.of(id.toString(), code, userId.toString(), subject,
                    claimUserId.toString(), machineSystemId.toString(), config.machineSystemCode(), customerId,
                    clientId));
            rowIds.forEach(row -> markers.add(row.toString()));
            markers.addAll(labels);
            markers.addAll(config.markers());
            LANES.forEach(lane -> markers.addAll(lane.markers(this)));
            return markers;
        }
    }

    /**
     * The tenant's config chain the admin API drives (T-015/T-019/T-021 surface): one system pair, a source
     * contract with its 1.0 draft version, and six flows in the states the lifecycle endpoints need. The suite
     * re-writes these exact ids before every test, so one entry's write never leaks into the next entry's run.
     */
    record Config(String word, UUID srcSystemId, UUID dstSystemId, UUID dstEndpointId, UUID contractId,
            UUID sourceContractVersionId, UUID flowMainId, UUID flowDraftId, UUID flowSubmitId,
            UUID flowApproveId, UUID flowPublishId, UUID flowImmutableId, UUID immutableVersionId,
            UUID pubRouteId, UUID pubMappingId, UUID otherAuthorId) {

        String srcCode() {
            return "src-" + word;
        }

        String dstCode() {
            return "dst-" + word;
        }

        String flowCode(String kind) {
            return "flow-" + word + "-" + kind;
        }

        /** The machine credential's source system: seeded once, never reset (source_credential references it). */
        String machineSystemCode() {
            return "mach-" + word;
        }

        String freshSystemCode() {
            return "sys-" + word + "-fresh";
        }

        List<String> markers() {
            return List.of(srcCode(), dstCode(), freshSystemCode(),
                    flowCode("main"), flowCode("draft"), flowCode("submit"), flowCode("approve"),
                    flowCode("publish"), flowCode("immutable"),
                    srcSystemId.toString(), dstSystemId.toString(), dstEndpointId.toString(),
                    contractId.toString(), sourceContractVersionId.toString(),
                    flowMainId.toString(), flowDraftId.toString(), flowSubmitId.toString(),
                    flowApproveId.toString(), flowPublishId.toString(), flowImmutableId.toString(),
                    immutableVersionId.toString(), otherAuthorId.toString());
        }
    }

    /**
     * The row-state probe of a lifecycle endpoint: {@code stateSql} (run on the owner connection) answers one
     * text scalar naming the seeded state and the state the legitimate write must leave behind. The suite
     * checks it after the legitimate call and after every forgery: a forged call must answer 404 without
     * touching tenant A's rows, and an ignored forgery must still write only inside tenant A.
     */
    record WriteProbe(Function<Seed, String> stateSql, String seeded, String afterWrite) {
    }

    /**
     * One endpoint and how the suite calls it for tenant A.
     *
     * @param method         the HTTP method
     * @param pattern        the mapping pattern exactly as registered
     * @param acceptsBody    whether the handler takes a {@code @RequestBody} (the guard checks it)
     * @param pathForA       the concrete path of a legitimate tenant-A call
     * @param foreignIdPath  the same path naming a tenant-B record id (only for {@code {id}} patterns)
     * @param bodyForA       the JSON body of a legitimate call (only when it accepts a body)
     * @param tenantAData    the tenant-A values a legitimate answer must contain (empty for an endpoint that
     *                       answers with static text: the marker check still runs, there is simply nothing to echo)
     * @param expectedStatus the status the scenario's legitimate call answers with (200 unless the API is designed
     *                       to answer otherwise: a create is 201, the immutable-version refusal is 409)
     * @param write          the row-state probe of a lifecycle endpoint, null when the endpoint writes nothing
     * @param extraHeaders   the entry's request headers beyond identity, forgery and content type (row 278 T104,
     *                       arjun-approved: an endpoint whose API contract demands a header - intake's
     *                       {@code X-Source-Contract-Version} and {@code Idempotency-Key} - supplies them here;
     *                       the function runs per request, so a per-call nonce is generated inside it)
     */
    record Entry(String method, String pattern, boolean acceptsBody, Function<Seed, String> pathForA,
            Function<Seed, String> foreignIdPath, Function<Seed, String> bodyForA,
            Function<Seed, List<String>> tenantAData, int expectedStatus, WriteProbe write,
            Function<Seed, Map<String, String>> extraHeaders) {

        Entry(String method, String pattern, boolean acceptsBody, Function<Seed, String> pathForA,
                Function<Seed, String> foreignIdPath, Function<Seed, String> bodyForA,
                Function<Seed, List<String>> tenantAData) {
            this(method, pattern, acceptsBody, pathForA, foreignIdPath, bodyForA, tenantAData, 200, null,
                    s -> Map.of());
        }

        Entry(String method, String pattern, boolean acceptsBody, Function<Seed, String> pathForA,
                Function<Seed, String> foreignIdPath, Function<Seed, String> bodyForA,
                Function<Seed, List<String>> tenantAData, int expectedStatus) {
            this(method, pattern, acceptsBody, pathForA, foreignIdPath, bodyForA, tenantAData, expectedStatus, null,
                    s -> Map.of());
        }

        Entry(String method, String pattern, boolean acceptsBody, Function<Seed, String> pathForA,
                Function<Seed, String> foreignIdPath, Function<Seed, String> bodyForA,
                Function<Seed, List<String>> tenantAData, int expectedStatus, WriteProbe write) {
            this(method, pattern, acceptsBody, pathForA, foreignIdPath, bodyForA, tenantAData, expectedStatus, write,
                    s -> Map.of());
        }

        String key() {
            return method + " " + pattern;
        }

        boolean hasIdPath() {
            return pattern.contains("{");
        }

        @Override
        public String toString() {
            return key();
        }
    }

    private static final String PROBE = "/api/v1/test-probe/rows";
    private static final String FLOWS = "/api/v1/admin/flows";
    private static final String FLOW_VERSIONS = "/api/v1/admin/flows/{flowId}/versions/{versionId}";
    private static final String CONTRACT_VERSIONS = "/api/v1/admin/contracts/versions";
    private static final String SYSTEMS = "/api/v1/admin/systems";

    /** Every /api/** endpoint in E1 and E3: lane B's /api/v1/me, the test probe, and lane C's config admin API. */
    private static final List<Entry> CORE = List.of(
            new Entry("GET", "/api/v1/me", false,
                    s -> "/api/v1/me", null, null,
                    s -> List.of(s.a().userId().toString(), s.a().id().toString(), s.a().code())),
            new Entry("GET", PROBE, false,
                    s -> PROBE, null, null,
                    s -> ids(s.a().rowIds())),
            new Entry("GET", PROBE + "/{id}", false,
                    s -> PROBE + "/" + s.a().rowIds().getFirst(),
                    s -> PROBE + "/" + s.b().rowIds().getFirst(),
                    null,
                    s -> List.of(s.a().rowIds().getFirst().toString())),
            new Entry("POST", PROBE + "/search", true,
                    s -> PROBE + "/search", null,
                    s -> "{\"labelPrefix\":\"\"}",
                    s -> ids(s.a().rowIds())),

            // --- T-015: systems, flows create/read (lane C's config admin API) ---------------------------------
            new Entry("POST", SYSTEMS, true,
                    s -> SYSTEMS, null,
                    s -> "{\"code\":\"" + s.a().config().freshSystemCode()
                            + "\",\"name\":\"fresh system\",\"direction\":\"OUTBOUND\"}",
                    s -> List.of(s.a().config().freshSystemCode(), "OUTBOUND"),
                    201,
                    // 3 systems: source, target and the machine credential's source system (seeded once, never reset)
                    new WriteProbe(tenantState("system", "target_endpoint"), "3|1", "4|2")),
            new Entry("GET", FLOWS, false,
                    s -> FLOWS, null, null,
                    s -> List.of(s.a().config().flowMainId().toString(),
                            s.a().config().flowPublishId().toString(),
                            s.a().config().flowCode("main"))),
            new Entry("GET", FLOWS + "/{flowId}", false,
                    s -> FLOWS + "/" + s.a().config().flowMainId(),
                    s -> FLOWS + "/" + s.b().config().flowMainId(),
                    null,
                    s -> List.of(s.a().config().flowMainId().toString(),
                            s.a().config().flowCode("main"), s.a().config().srcCode())),

            // --- T-019: the draft-save and the lifecycle walk --------------------------------------------------
            new Entry("PUT", FLOWS + "/{flowId}", true,
                    s -> FLOWS + "/" + s.a().config().flowDraftId(),
                    s -> FLOWS + "/" + s.b().config().flowDraftId(),
                    s -> flowBody(s),
                    s -> List.of(s.a().config().flowDraftId().toString()),
                    200,
                    new WriteProbe(s -> "SELECT (SELECT count(*) FROM public.interface_version"
                            + " WHERE interface_id = '" + s.a().config().flowDraftId() + "')"
                            + " || '|' || (SELECT current_version FROM public.interface"
                            + " WHERE id = '" + s.a().config().flowDraftId() + "')", "1|1", "2|2")),
            new Entry("PUT", FLOW_VERSIONS, false,
                    s -> FLOWS + "/" + s.a().config().flowImmutableId() + "/versions/"
                            + s.a().config().immutableVersionId(),
                    s -> FLOWS + "/" + s.b().config().flowImmutableId() + "/versions/"
                            + s.b().config().immutableVersionId(),
                    null,
                    s -> List.of(), 409),
            new Entry("POST", FLOWS + "/{flowId}/submit", true,
                    s -> FLOWS + "/" + s.a().config().flowSubmitId() + "/submit",
                    s -> FLOWS + "/" + s.b().config().flowSubmitId() + "/submit",
                    s -> "{\"changeNote\":\"submit frozen note\"}",
                    s -> List.of(s.a().config().flowSubmitId().toString()),
                    200,
                    new WriteProbe(s -> "SELECT version || '|' || status || '/n=' || coalesce(change_note, '-')"
                            + " FROM public.interface_version WHERE interface_id = '"
                            + s.a().config().flowSubmitId() + "'",
                            "1|DRAFT/n=-", "1|PENDING_APPROVAL/n=submit frozen note")),
            new Entry("POST", FLOWS + "/{flowId}/approve", false,
                    s -> FLOWS + "/" + s.a().config().flowApproveId() + "/approve",
                    s -> FLOWS + "/" + s.b().config().flowApproveId() + "/approve",
                    null,
                    s -> List.of(s.a().config().flowApproveId().toString()),
                    200,
                    new WriteProbe(s -> "SELECT status || '/appr=' || (approver_id IS NOT NULL)::text"
                            + " FROM public.interface_version WHERE interface_id = '"
                            + s.a().config().flowApproveId() + "'",
                            "PENDING_APPROVAL/appr=false", "APPROVED/appr=true")),
            new Entry("POST", FLOWS + "/{flowId}/publish", false,
                    s -> FLOWS + "/" + s.a().config().flowPublishId() + "/publish",
                    s -> FLOWS + "/" + s.b().config().flowPublishId() + "/publish",
                    null,
                    s -> List.of(s.a().config().flowPublishId().toString()),
                    200,
                    new WriteProbe(s -> "SELECT i.status || '|' || coalesce(i.published_version::text, '-')"
                            + " || '|' || v.status || '|r'"
                            + " || (SELECT count(*) FROM public.route r WHERE r.interface_id = i.id) || '|m'"
                            + " || (SELECT count(*) FROM public.mapping m WHERE m.route_id IN"
                            + " (SELECT r.id FROM public.route r WHERE r.interface_id = i.id))"
                            + " FROM public.interface i, public.interface_version v"
                            + " WHERE i.id = '" + s.a().config().flowPublishId() + "'"
                            + " AND v.interface_id = i.id AND v.version = 1",
                            // row 278 T84: the snapshot's route + mapping are live before publish (E7MappingMatrix)
                            "DRAFT|-|APPROVED|r1|m1", "PUBLISHED|1|PUBLISHED|r1|m1")),

            // --- T-019: the contract draft-save, read and submit -----------------------------------------------
            new Entry("POST", CONTRACT_VERSIONS, true,
                    s -> CONTRACT_VERSIONS, null,
                    s -> "{\"system\":\"" + s.a().config().srcCode()
                            + "\",\"role\":\"SOURCE_REQUEST\",\"name\":\"fresh draft\","
                            + "\"sourceSchema\":{\"type\":\"object\"}}",
                    s -> List.of("1.1", "DRAFT"),
                    200,
                    new WriteProbe(s -> "SELECT count(*) || '|@' || coalesce(string_agg(version_label, ','"
                            + " ORDER BY version_label), '-') FROM public.contract_version WHERE tenant_id = '"
                            // row 278 T104 (arjun-approved rebase): E11IntakeMatrix pins one extra APPROVED
                            // contract_version ("1.0") per tenant.
                            + s.a().id() + "'", "3|@1.0,1.0,1.0", "4|@1.0,1.0,1.0,1.1")),
            new Entry("GET", CONTRACT_VERSIONS + "/{versionId}", false,
                    s -> CONTRACT_VERSIONS + "/" + s.a().config().sourceContractVersionId(),
                    s -> CONTRACT_VERSIONS + "/" + s.b().config().sourceContractVersionId(),
                    null,
                    s -> List.of(s.a().config().sourceContractVersionId().toString(), "1.0",
                            s.a().config().srcCode())),
            new Entry("POST", CONTRACT_VERSIONS + "/{versionId}/submit", true,
                    s -> CONTRACT_VERSIONS + "/" + s.a().config().sourceContractVersionId() + "/submit",
                    s -> CONTRACT_VERSIONS + "/" + s.b().config().sourceContractVersionId() + "/submit",
                    s -> "{\"changeNote\":\"contract frozen note\"}",
                    s -> List.of(s.a().config().sourceContractVersionId().toString()),
                    200,
                    new WriteProbe(s -> "SELECT status || '/n=' || coalesce(change_note, '-')"
                            + " FROM public.contract_version WHERE id = '"
                            + s.a().config().sourceContractVersionId() + "'",
                            "DRAFT/n=-", "PENDING_APPROVAL/n=contract frozen note")),

            // --- T-015: the flow creation (POST /flows answers 201, its rows land in tenant A only) -------------
            new Entry("POST", FLOWS, true,
                    s -> FLOWS, null,
                    s -> flowBody(s),
                    s -> List.of(),
                    201,
                    // row 278 T104 (arjun-approved rebase): E11IntakeMatrix seeds one interface + version per tenant.
                    new WriteProbe(tenantState("interface", "interface_version"), "7|7", "8|8")));

    /**
     * E5 seam (arjun-fullstack, F13): each epic lane's part of the matrix, in its own file ({@link LaneMatrix}). A new
     * lane part is one line here, written by the integrator, never by a lane.
     */
    static final List<LaneMatrix> LANES = List.of(new E5ContractMatrix(), new E5SystemMatrix(), new E5SecretMatrix(),
            new E6RuleMatrix(), new E7MappingMatrix(), new E7LookupMatrix(), new E7MappingTestMatrix(),
            // row 278 T104: before F10 - the intake acceptance family (message/payload/outbox/timing) has no FKs, so
            // E11's children are reachable by their own ids only while its parent message row still stands, and
            // F10's clear removes the tenant's whole message set.
            new B9FlowRoutesMatrix(), new E11IntakeMatrix(), new F10QueueSummaryMatrix());

    /** The whole matrix: the core entries above, then every lane's entries. */
    static final List<Entry> REGISTRY = Stream.concat(CORE.stream(),
            LANES.stream().flatMap(lane -> lane.entries().stream())).toList();

    private EndpointMatrix() {
    }

    /** The legitimate flow body both write entries send: a real tenant-A onboarding shape. */
    private static String flowBody(Seed s) {
        return "{\"businessKeyRule\":\"$.orderId\",\"sourceSystem\":\"" + s.a().config().srcCode()
                + "\",\"destinations\":[\"" + s.a().config().dstCode() + "\"]}";
    }

    /** The row count of two config tables inside tenant A, e.g. "6|6" before and "7|7" after the legitimate write. */
    private static Function<Seed, String> tenantState(String table, String childTable) {
        return s -> "SELECT (SELECT count(*) FROM public." + table + " WHERE tenant_id = '" + s.a().id() + "')"
                + " || '|' || (SELECT count(*) FROM public." + childTable
                + " WHERE tenant_id = '" + s.a().id() + "')";
    }

    /** Every (entry, forgery) pair that fits - the scenario outline's rows. */
    static Stream<Object[]> forgeries() {
        return REGISTRY.stream().flatMap(entry -> Arrays.stream(Forgery.values())
                .filter(forgery -> forgery.fits(entry))
                .map(forgery -> new Object[] {entry, forgery}));
    }

    /** Every (entry, source forgery) pair that fits - the machine channel's rows (E4-F1-S4). */
    static Stream<Object[]> sourceForgeries() {
        return REGISTRY.stream().flatMap(entry -> Arrays.stream(SourceForgery.values())
                .filter(forgery -> forgery.fits(entry))
                .map(forgery -> new Object[] {entry, forgery}));
    }

    /** Every (entry, tenant field) pair for the unauthenticated scenario. */
    static Stream<Object[]> tenantFields() {
        return forgeries().filter(pair -> ((Forgery) pair[1]).isTenantField());
    }

    /**
     * The coverage guard over a handler mapping's registrations.
     *
     * @return one message per problem; empty when every {@code /api/**} endpoint has exactly the entry it needs
     */
    static List<String> coverageProblems(Map<RequestMappingInfo, HandlerMethod> mappings, List<Entry> registry) {
        Map<String, Entry> registered = new LinkedHashMap<>();
        registry.forEach(entry -> registered.put(entry.key(), entry));
        Set<String> discovered = new TreeSet<>();
        List<String> problems = new ArrayList<>();

        mappings.forEach((info, handler) -> {
            boolean takesBody = Arrays.stream(handler.getMethodParameters())
                    .anyMatch(parameter -> parameter.hasParameterAnnotation(RequestBody.class));
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            List<String> verbs = methods.isEmpty() ? List.of("*") : methods.stream().map(Enum::name).toList();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.equals("/api") && !pattern.startsWith("/api/")) {
                    continue;
                }
                for (String verb : verbs) {
                    String key = verb + " " + pattern;
                    discovered.add(key);
                    Entry entry = registered.get(key);
                    if (entry == null) {
                        problems.add("no matrix entry for " + key + " (" + handler.getShortLogMessage() + ")");
                    } else if (entry.acceptsBody() != takesBody) {
                        problems.add("matrix entry " + key + " says acceptsBody=" + entry.acceptsBody()
                                + " but the handler " + (takesBody ? "takes" : "takes no") + " @RequestBody");
                    }
                }
            }
        });
        registered.keySet().stream().filter(key -> !discovered.contains(key))
                .forEach(key -> problems.add("stale matrix entry, no such endpoint: " + key));
        return problems;
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }
}
