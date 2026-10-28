package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.EndpointAuth;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 510: saving an endpoint's authentication. The OAuth2 token URL receives the sealed client secret, so it passes the
 * destination guard at save (no DNS) and a refusal is a 400 that leaves the row unchanged and repeats no host; the
 * API_KEY header name is checked for line breaks and reserved names; the token URL is cleared when the method is not
 * OAuth2; and every change writes one audit event (actor, system, method and identity, never a secret or the URL path).
 */
class SystemAdminServiceEndpointAuthTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String ID = "88888888-8888-8888-8888-888888888888";

    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final SystemAdminService service = new SystemAdminService(systems, mock(TestConnectionService.class),
            new DestinationGuard(AllowList.of(List.of("auth.partner.example", "127.0.0.1", "kafka")),
                    HostResolver.system(), List.of(), List.of(), List.of("kafka")),
            FileRoots.none(), () -> Optional.of(TENANT), false, events::add);

    private void stored() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(
                new SystemDetail(ID, "orders", "Orders", "TARGET", null, null)));
        when(systems.findEndpointAuth(ID)).thenReturn(Optional.of(new EndpointAuth("ep-1", "NO_AUTH", null, null)));
        when(systems.updateEndpointAuth(anyString(), anyString(), any(), any())).thenReturn(1);
    }

    private static JsonNode oauth(String tokenUrl) {
        return JSON.readTree("{\"authMethod\":\"OAUTH2_CLIENT_CREDENTIALS\",\"authIdentity\":\"cid\",\"authTokenUrl\":"
                + JSON.writeValueAsString(tokenUrl) + "}");
    }

    @ParameterizedTest(name = "[{index}] token URL {0} is a 400 and the row is unchanged")
    @ValueSource(strings = {"http://127.0.0.1:9/t", "https://127.0.0.1/t", "http://169.254.169.254/x", "http://[::1]/t",
            "http://kafka:9092/", "https://kafka/t", "https://u:p@auth.partner.example/t", "https://not-listed.example/t",
            "http://auth.partner.example/t", "file:///etc/passwd", "ftp://auth.partner.example/t", "not a url ::",
            "https:///t"})
    void aRefusedTokenUrlIsRejectedAtSave(String tokenUrl) {
        stored();

        assertThatThrownBy(() -> service.updateEndpointAuth(ID, oauth(tokenUrl), ACTOR))
                .isInstanceOfSatisfying(SystemValidationException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    String text = String.valueOf(e.body());
                    assertThat(text).contains("authTokenUrl");
                    assertThat(text).doesNotContain("127.0.0.1").doesNotContain("kafka")
                            .doesNotContain("169.254").doesNotContain("partner").doesNotContain("not-listed")
                            .doesNotContain("passwd");
                });
        verify(systems, never()).updateEndpointAuth(anyString(), anyString(), any(), any());
        assertThat(events).as("no audit event for a refused save").isEmpty();
    }

    @Test
    @DisplayName("an allow-listed https token URL is saved, and one audit event names actor, system, method and identity only")
    void aListedHttpsTokenUrlIsSavedAndAudited() {
        stored();

        var view = service.updateEndpointAuth(ID, oauth("https://auth.partner.example/oauth/token?x=SECRET"), ACTOR);

        assertThat(view.get("authTokenUrl")).isEqualTo("https://auth.partner.example/oauth/token?x=SECRET");
        verify(systems).updateEndpointAuth(ID, "OAUTH2_CLIENT_CREDENTIALS", "cid",
                "https://auth.partner.example/oauth/token?x=SECRET");
        assertThat(events).singleElement().isInstanceOf(AuditEvent.class);
        AuditEvent event = (AuditEvent) events.get(0);
        assertThat(event.action()).isEqualTo(ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.targetIds()).contains("system:" + ID);
        assertThat(event.before()).contains("NO_AUTH");
        assertThat(event.after()).contains("OAUTH2_CLIENT_CREDENTIALS").contains("cid")
                .contains("auth.partner.example").doesNotContain("SECRET").doesNotContain("/oauth");
    }

    @Test
    @DisplayName("the token URL is cleared when the method is not OAuth2 (a stale URL never lingers)")
    void theTokenUrlIsClearedForOtherMethods() {
        stored();

        service.updateEndpointAuth(ID, JSON.readTree(
                "{\"authMethod\":\"BEARER_TOKEN\",\"authTokenUrl\":\"https://auth.partner.example/t\"}"), ACTOR);

        verify(systems).updateEndpointAuth(ID, "BEARER_TOKEN", null, null);
    }

    @ParameterizedTest(name = "[{index}] API_KEY header name \"{0}\" is a 400")
    @ValueSource(strings = {"X-Key\\r\\nX-Evil: 1", "Idempotency-Key", "content-type", "Host", "Authorization", "X Key",
            "X-Key:"})
    void aBadApiKeyHeaderNameIsRejected(String headerName) {
        stored();

        assertThatThrownBy(() -> service.updateEndpointAuth(ID,
                JSON.readTree("{\"authMethod\":\"API_KEY\",\"authIdentity\":\"" + headerName + "\"}"), ACTOR))
                .isInstanceOf(SystemValidationException.class);
        verify(systems, never()).updateEndpointAuth(anyString(), anyString(), any(), any());
    }

    @Test
    void aPlainApiKeyHeaderNameIsSaved() {
        stored();

        service.updateEndpointAuth(ID, JSON.readTree("{\"authMethod\":\"API_KEY\",\"authIdentity\":\"X-Api-Key\"}"),
                ACTOR);

        verify(systems).updateEndpointAuth(ID, "API_KEY", "X-Api-Key", null);
        assertThat(events).hasSize(1);
    }
}
