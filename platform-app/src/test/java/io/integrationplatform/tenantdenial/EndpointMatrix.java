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
 * Every new endpoint of every module adds its entry here; the coverage guard fails the suite when an endpoint has
 * none, when an entry no longer matches an endpoint, or when an entry misstates whether the endpoint takes a body.
 * Which forgeries apply is derived from the entry ({@link Forgery#fits}).
 */
final class EndpointMatrix {

    /** Two seeded tenants; tenant A's user signs in, tenant B is the one the forgeries name. */
    record Seed(SeededTenant a, SeededTenant b) {
    }

    /** One tenant with its ACTIVE user and its probe rows (labels carry the tenant's word, e.g. "alpha-1"). */
    record SeededTenant(UUID id, String code, UUID userId, String subject, List<UUID> rowIds, List<String> labels) {

        /** Every value that identifies this tenant's data; none of them may reach the other tenant's user. */
        List<String> markers() {
            List<String> markers = new ArrayList<>(List.of(id.toString(), code, userId.toString(), subject));
            rowIds.forEach(row -> markers.add(row.toString()));
            markers.addAll(labels);
            return markers;
        }
    }

    /**
     * One endpoint and how the suite calls it for tenant A.
     *
     * @param method        the HTTP method
     * @param pattern       the mapping pattern exactly as registered
     * @param acceptsBody   whether the handler takes a {@code @RequestBody} (the guard checks it)
     * @param pathForA      the concrete path of a legitimate tenant-A call
     * @param foreignIdPath the same path naming a tenant-B record id (only for {@code {id}} patterns)
     * @param bodyForA      the JSON body of a legitimate call (only when it accepts a body)
     * @param tenantAData   the tenant-A values a legitimate answer must contain
     */
    record Entry(String method, String pattern, boolean acceptsBody, Function<Seed, String> pathForA,
            Function<Seed, String> foreignIdPath, Function<Seed, String> bodyForA,
            Function<Seed, List<String>> tenantAData) {

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

    /** Every /api/** endpoint in E1: lane B's /api/v1/me (fixed contract) and the test probe. */
    static final List<Entry> REGISTRY = List.of(
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
                    s -> ids(s.a().rowIds())));

    private EndpointMatrix() {
    }

    /** Every (entry, forgery) pair that fits - the scenario outline's rows. */
    static Stream<Object[]> forgeries() {
        return REGISTRY.stream().flatMap(entry -> Arrays.stream(Forgery.values())
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
