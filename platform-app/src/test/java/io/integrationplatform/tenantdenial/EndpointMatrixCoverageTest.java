package io.integrationplatform.tenantdenial;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt.Channel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The coverage guard of the forged-tenant matrix fails on a planted violation (R02), without Docker: the handlers are
 * registered in a bare handler mapping, never in the application. Also checks that the matrix exercises every Examples
 * row of the scenario outline, so no forgery is silently left out. Not tagged container (R03).
 */
class EndpointMatrixCoverageTest {

    @Test
    void theE1EndpointsAreFullyCovered() {
        Map<RequestMappingInfo, HandlerMethod> mappings = mappingsOf(probeController(), new MeContract());

        assertThat(EndpointMatrix.coverageProblems(mappings, EndpointMatrix.REGISTRY)).isEmpty();
    }

    @Test
    void aPlantedUnregisteredEndpointFailsTheGuard() {
        Map<RequestMappingInfo, HandlerMethod> mappings =
                mappingsOf(probeController(), new MeContract(), new PlantedUnregisteredEndpoint());

        assertThat(EndpointMatrix.coverageProblems(mappings, EndpointMatrix.REGISTRY))
                .singleElement().asString()
                .startsWith("no matrix entry for GET /api/v1/planted-records/{id}");
    }

    @Test
    void aPlantedBodyEndpointRegisteredWithoutItsBodyFailsTheGuard() {
        List<EndpointMatrix.Entry> misstated = List.of(new EndpointMatrix.Entry(
                "POST", "/api/v1/planted-records", false, s -> "/api/v1/planted-records", null, null, s -> List.of()));

        assertThat(EndpointMatrix.coverageProblems(mappingsOf(new PlantedBodyEndpoint()), misstated))
                .singleElement().asString().contains("acceptsBody=false").contains("takes @RequestBody");
    }

    @Test
    void anEntryWithoutAnEndpointFailsTheGuard() {
        assertThat(EndpointMatrix.coverageProblems(mappingsOf(probeController()), EndpointMatrix.REGISTRY))
                .containsExactly("stale matrix entry, no such endpoint: GET /api/v1/me");
    }

    @Test
    void nonApiMappingsAreOutOfScope() {
        assertThat(EndpointMatrix.coverageProblems(mappingsOf(new NonApiEndpoint()), List.of())).isEmpty();
    }

    @Test
    void theMatrixExercisesEveryExamplesRowOfTheOutline() {
        Set<Forgery> exercised = EndpointMatrix.forgeries().map(pair -> (Forgery) pair[1]).collect(Collectors.toSet());

        assertThat(exercised).containsExactlyInAnyOrder(Forgery.values());
        assertThat(Arrays.stream(Forgery.values()).map(Forgery::example)).containsExactly(
                "a \"Tenant\" header",
                "a tenantId field in the body",
                "a tenantId query parameter",
                "a tenant-B record id in the path");
        assertThat(Arrays.stream(Forgery.values()).map(Forgery::channel))
                .containsExactly(Channel.HEADER, Channel.BODY_FIELD, Channel.QUERY_PARAMETER, Channel.PATH_ID);
    }

    @Test
    void headerAndQueryForgeriesReachEveryEndpointAndTheUnauthenticatedScenarioCarriesTenantFieldsOnly() {
        for (EndpointMatrix.Entry entry : EndpointMatrix.REGISTRY) {
            assertThat(Forgery.HEADER.fits(entry)).isTrue();
            assertThat(Forgery.QUERY_PARAMETER.fits(entry)).isTrue();
        }
        assertThat(EndpointMatrix.tenantFields().map(pair -> (Forgery) pair[1])).doesNotContain(Forgery.PATH_ID);
        assertThat(EndpointMatrix.tenantFields().count())
                .isEqualTo(EndpointMatrix.forgeries().filter(pair -> pair[1] != Forgery.PATH_ID).count());
    }

    // --- planted and contract handlers: none is a @Controller, so the application never registers them --------

    /** Lane B's fixed E1 contract: GET /api/v1/me (the real controller arrives with lane B). */
    static class MeContract {
        @GetMapping("/api/v1/me")
        Object me() {
            return null;
        }
    }

    /** R02 planted violation: an /api/** endpoint with no matrix entry. */
    static class PlantedUnregisteredEndpoint {
        @GetMapping("/api/v1/planted-records/{id}")
        Object one(@PathVariable UUID id) {
            return null;
        }
    }

    static class PlantedBodyEndpoint {
        @PostMapping("/api/v1/planted-records")
        Object create(@RequestBody Map<String, Object> body) {
            return null;
        }
    }

    static class NonApiEndpoint {
        @GetMapping("/internal/ping")
        Object ping() {
            return null;
        }
    }

    private static TestProbeController probeController() {
        return new TestProbeController(null, null, null); // only its mappings are read
    }

    private static Map<RequestMappingInfo, HandlerMethod> mappingsOf(Object... handlers) {
        BareHandlerMapping mapping = new BareHandlerMapping();
        for (Object handler : handlers) {
            mapping.register(handler);
        }
        return mapping.getHandlerMethods();
    }

    /** A handler mapping outside any application context's bean scan: handlers are registered one by one. */
    private static final class BareHandlerMapping extends RequestMappingHandlerMapping {

        BareHandlerMapping() {
            GenericApplicationContext empty = new GenericApplicationContext();
            empty.refresh();
            setApplicationContext(empty);
            afterPropertiesSet();
        }

        void register(Object handler) {
            detectHandlerMethods(handler);
        }
    }
}
