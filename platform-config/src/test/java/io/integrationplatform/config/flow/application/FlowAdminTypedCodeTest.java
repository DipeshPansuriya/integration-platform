package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Row 482 (PR 26 follow-up, review F1 / F2 / F17): the typed interface code on flow create. It is the intake URL
 * segment and never updatable, so it is pinned here: stored as typed (trimmed) and the very code the flow answers
 * with, minted ({@code flow-<uuid>}) when none is typed, and refused with a clear 4xx - before any SQL - when it
 * could not be addressed (charset, length, not a string). The same code twice in one tenant is a named 409.
 * Tenant-scoped uniqueness itself is the database's (V2_2_3, {@code T015FlowAdminContainerTest}).
 */
class FlowAdminTypedCodeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final String V1 = "22222222-2222-2222-2222-222222222222";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final SystemAdminService systems = mock(SystemAdminService.class);
    private final FlowAdminService service = new FlowAdminService(flows, systems, mock(ContractAdminService.class),
            mock(MappingPublishPinCheck.class), event -> { }, () -> Optional.of(TENANT));

    private void aWorkingRepository() {
        when(systems.systemExists("SRC")).thenReturn(true);
        when(systems.endpointFor("TGT")).thenReturn("ep-1");
        when(flows.insertInterface(anyString(), anyString(), anyString())).thenReturn(FLOW);
        when(flows.insertVersion(any(), anyInt(), any(), any(), any(), any())).thenReturn(V1);
    }

    private static ObjectNode createBody() {
        ObjectNode body = JSON.createObjectNode();
        body.put("businessKeyRule", "$.id");
        body.put("sourceSystem", "SRC");
        body.putArray("destinations").add("TGT");
        return body;
    }

    private InterfaceRow createWith(JsonNode body) {
        return service.create(body, ACTOR);
    }

    private static void refusedAs400NamingTheCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConfigApiException.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(400);
            assertThat(String.valueOf(e.body().get("error"))).contains("code");
        });
    }

    @Test
    void aTypedCodeIsStoredTrimmedAndIsTheCodeTheFlowAnswersWith() {
        aWorkingRepository();
        ObjectNode body = createBody();
        body.put("code", "  ORD-1.v2_a  ");

        InterfaceRow created = createWith(body);

        assertThat(created.code()).isEqualTo("ORD-1.v2_a");
        assertThat(created.name()).as("no typed name: the name falls back to the typed code").isEqualTo("ORD-1.v2_a");
        verify(flows).insertInterface("ORD-1.v2_a", "ORD-1.v2_a", "PER_KEY");
    }

    @Test
    void aTypedNameDoesNotChangeTheTypedCode() {
        aWorkingRepository();
        ObjectNode body = createBody();
        body.put("code", "orders");
        body.put("name", "Customer orders");

        assertThat(createWith(body).code()).isEqualTo("orders");
        verify(flows).insertInterface("orders", "Customer orders", "PER_KEY");
    }

    @Test
    void theLongestAndTheShortestAcceptedCodesAreStored() {
        aWorkingRepository();
        String longest = "a" + "b".repeat(62);
        ObjectNode longBody = createBody();
        longBody.put("code", longest);
        ObjectNode shortBody = createBody();
        shortBody.put("code", "7");

        assertThat(createWith(longBody).code()).hasSize(63).isEqualTo(longest);
        assertThat(createWith(shortBody).code()).isEqualTo("7");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void noBlankOrAbsentCodeMintsTheGeneratedFlowCode(String blank) {
        aWorkingRepository();
        ObjectNode withCode = createBody();
        if (blank == null) {
            withCode.putNull("code");
        } else {
            withCode.put("code", blank);
        }

        assertThat(createWith(withCode).code()).matches("flow-[0-9a-f-]{36}");
        assertThat(createWith(createBody()).code()).as("absent key").matches("flow-[0-9a-f-]{36}");
    }

    @Test
    void twoMintedCodesDiffer() {
        aWorkingRepository();

        assertThat(createWith(createBody()).code()).isNotEqualTo(createWith(createBody()).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "a/b", "a?b", "a#b", "a%2Fb", "a\\b", "a:b", "a;b", "a@b", "a\"b", "a'b",
            "a<b>", "a\nb", "a\u0000b", "../x", "-lead", ".lead", "_lead", "café", "نظام",
            "a😀", "; drop table interface; --",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl"}) // the last is 64 characters
    void aCodeThatCouldNotBeAddressedIsRefusedBeforeAnySql(String illegal) {
        aWorkingRepository();
        ObjectNode body = createBody();
        body.put("code", illegal);

        refusedAs400NamingTheCode(() -> createWith(body));
        verify(flows, never()).insertInterface(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true", "{\"a\":1}", "[\"x\"]", "1.5"})
    void aCodeThatIsNotAJsonStringIsRefusedNotCoerced(String raw) {
        aWorkingRepository();
        ObjectNode body = createBody();
        body.set("code", JSON.readTree(raw));

        refusedAs400NamingTheCode(() -> createWith(body));
        verify(flows, never()).insertInterface(anyString(), anyString(), anyString());
    }

    @Test
    void theSameCodeTwiceInOneTenantIsANamed409() {
        aWorkingRepository();
        when(flows.insertInterface(anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("interface_tenant_code_key"));
        ObjectNode body = createBody();
        body.put("code", "orders");

        assertThatThrownBy(() -> createWith(body)).isInstanceOfSatisfying(ConfigApiException.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(409);
            assertThat(String.valueOf(e.body().get("error"))).contains("code").contains("orders").contains("already");
        });
    }
}
