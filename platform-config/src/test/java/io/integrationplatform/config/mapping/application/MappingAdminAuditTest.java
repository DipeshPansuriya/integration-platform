package io.integrationplatform.config.mapping.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository;
import io.integrationplatform.config.mapping.internal.persistence.JdbcMappingRepository.LiveMapping;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12-F2-S1 "One event per action" for the mapping edit (row 327 T117, T-068): a saved mapping publishes exactly
 * one {@link AuditEvent} naming the route and mapping, with the mapping version before and after - never the
 * expression or field links themselves; a refused save publishes none.
 */
class MappingAdminAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ROUTE = "33333333-3333-3333-3333-333333333333";
    private static final String MAPPING = "44444444-4444-4444-4444-444444444444";
    private static final String OLD_VERSION = "55555555-5555-5555-5555-555555555555";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final JdbcMappingRepository mappings = mock(JdbcMappingRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final MappingAdminService service = new MappingAdminService(mappings, events::add,
            () -> Optional.of(TENANT));

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    @Test
    void aSavedMappingPublishesOneMappingSavedEvent() {
        when(mappings.findMappingOfRoute(ROUTE)).thenReturn(Optional.of(
                new LiveMapping(MAPPING, "[]", "$.secretish", null, null, OLD_VERSION)));

        service.save(ROUTE, JSON.readTree("{\"compiledExpression\":\"$.order\"}"), ACTOR);

        assertThat(audits()).as("exactly one audit event for the save").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.MAPPING_SAVED);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.at()).isNotNull();
        assertThat(event.targetIds()).containsExactly("route:" + ROUTE, "mapping:" + MAPPING);
        assertThat(event.before()).isEqualTo("{\"mappingVersionId\":\"" + OLD_VERSION + "\"}");
        assertThat(event.after()).isEqualTo("{\"mappingVersionId\":null}");
        assertThat(event.before() + event.after()).as("no mapping content in the audit")
                .doesNotContain("$.order").doesNotContain("$.secretish");
    }

    @Test
    void aRefusedSavePublishesNothing() {
        when(mappings.findMappingOfRoute(ROUTE)).thenReturn(Optional.of(
                new LiveMapping(MAPPING, "[]", "$", null, null, null)));

        assertThatThrownBy(() -> service.save(ROUTE, JSON.readTree("{}"), ACTOR))
                .isInstanceOf(ConfigApiException.class);
        assertThat(audits()).isEmpty();
    }
}
