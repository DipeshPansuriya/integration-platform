package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review F-1 (audit of a destination change) and F-2 (a PUT that does not change the address never copies it):
 * the endpoint's delivery address follows {@code settings.baseUrl} only when a validated save changes it, and every
 * such change publishes one audit event naming the old and new HOST - never the path, query or userinfo (R14).
 */
class SystemAdminServiceDestinationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String ID = "88888888-8888-8888-8888-888888888888";
    private static final String OLD = "https://old.partner.example/v1?token=OLD-SECRET";
    private static final String NEW = "https://new.partner.example:8443/v2/orders?api_key=NEW-SECRET";

    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final SystemAdminService service = new SystemAdminService(systems, mock(TestConnectionService.class),
            new DestinationGuard(AllowList.of(List.of("*.partner.example")), HostResolver.system()), FileRoots.none(),
            () -> Optional.of(TENANT), false, events::add);

    private static JsonNode body(String direction, String baseUrl) {
        return JSON.readTree("{\"code\":\"orders\",\"name\":\"Orders\",\"direction\":\"" + direction
                + "\",\"type\":\"API\",\"settings\":{\"baseUrl\":\"" + baseUrl + "\"}}");
    }

    private static SystemDetail stored(String baseUrl) {
        return new SystemDetail(ID, "orders", "Orders", "TARGET", "API", "{\"baseUrl\":\"" + baseUrl + "\"}");
    }

    private AuditEvent onlyEvent() {
        assertThat(events).singleElement().isInstanceOf(AuditEvent.class);
        return (AuditEvent) events.get(0);
    }

    @Test
    @DisplayName("create: the endpoint is seeded with the address and one event names the new host only")
    void createAuditsTheNewHost() {
        when(systems.findSystemByCode("orders")).thenReturn(Optional.empty());
        when(systems.insertTypedSystem(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ID);

        service.create(body("OUTBOUND", NEW), ACTOR);

        verify(systems).insertDefaultEndpoint(ID, "orders", "Orders", NEW);
        AuditEvent event = onlyEvent();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.SYSTEM_DESTINATION_CHANGED);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.after()).contains("new.partner.example").doesNotContain("NEW-SECRET")
                .doesNotContain("/v2").doesNotContain("api_key");
    }

    @Test
    @DisplayName("create: an INBOUND system has no endpoint and no destination event")
    void inboundIsUntouched() {
        when(systems.findSystemByCode("orders")).thenReturn(Optional.empty());
        when(systems.insertTypedSystem(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ID);

        service.create(body("INBOUND", NEW), ACTOR);

        verify(systems, never()).insertDefaultEndpoint(anyString(), anyString(), anyString(), any());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("update: a changed address is written and audited with old and new host only")
    void updateAuditsOldAndNewHost() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(stored(OLD)));
        when(systems.findEndpointBaseUrl(ID)).thenReturn(Optional.of(OLD));
        when(systems.updateSystem(eq(ID), anyString(), anyString(), anyString())).thenReturn(1);

        service.update(ID, body("OUTBOUND", NEW), ACTOR);

        verify(systems).updateEndpointBaseUrl(ID, NEW);
        AuditEvent event = onlyEvent();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.SYSTEM_DESTINATION_CHANGED);
        assertThat(event.before()).contains("old.partner.example").doesNotContain("OLD-SECRET").doesNotContain("token");
        assertThat(event.after()).contains("new.partner.example").doesNotContain("NEW-SECRET").doesNotContain("api_key");
    }

    @Test
    @DisplayName("F-2: a PUT carrying the address the endpoint already has writes and audits nothing")
    void unchangedAddressWritesNothing() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(stored(NEW)));
        when(systems.findEndpointBaseUrl(ID)).thenReturn(Optional.of(NEW));
        when(systems.updateSystem(eq(ID), anyString(), anyString(), anyString())).thenReturn(1);

        service.update(ID, body("OUTBOUND", NEW), ACTOR);

        verify(systems, never()).updateEndpointBaseUrl(anyString(), anyString());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("F-2: a name-only PUT never copies the stored address into the endpoint (no guard ran for it)")
    void nameOnlyPutNeverWritesTheEndpoint() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(stored(NEW)));
        when(systems.findEndpointBaseUrl(ID)).thenReturn(Optional.of("https://endpoint.invalid/"));
        when(systems.updateSystem(eq(ID), anyString(), anyString(), anyString())).thenReturn(1);

        service.update(ID, JSON.readTree("{\"name\":\"Renamed\"}"), ACTOR);

        verify(systems, never()).updateEndpointBaseUrl(anyString(), anyString());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a refused address writes and audits nothing")
    void refusedAddressWritesNothing() {
        when(systems.findSystem(ID)).thenReturn(Optional.of(stored(OLD)));

        assertThatThrownBy(() -> service.update(ID, body("OUTBOUND", "https://evil.example/x"), ACTOR))
                .isInstanceOf(SystemValidationException.class);

        verify(systems, never()).updateEndpointBaseUrl(anyString(), anyString());
        assertThat(events).isEmpty();
    }
}
