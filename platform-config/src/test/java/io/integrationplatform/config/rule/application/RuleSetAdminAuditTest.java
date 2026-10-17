package io.integrationplatform.config.rule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * E12-F2-S1 "One event per action" for the rule-set edits (row 327 T117, T-068): a created and an updated rule
 * set each publish exactly one {@link AuditEvent} naming the flow and the rule set, with kind and name before and
 * after - never the decision table; a refused edit publishes none. Lookup-entry edits keep their own event
 * ({@link LookupEntryService#ACTION}).
 */
class RuleSetAdminAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID FLOW = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RULE_SET = "66666666-6666-6666-6666-666666666666";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String TABLE = "{\"nodes\":[{\"id\":\"t\",\"content\":{\"rules\":[{\"o1\":\"\\\"Germany\\\"\"}]}}]}";

    private final JdbcRuleSetRepository ruleSets = mock(JdbcRuleSetRepository.class);
    private final DecisionEngine engine = mock(DecisionEngine.class);
    private final List<Object> events = new ArrayList<>();
    private final RuleSetAdminService service = new RuleSetAdminService(ruleSets, engine, events::add,
            () -> Optional.of(TENANT));

    private List<AuditEvent> audits() {
        return events.stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }

    private AuditEvent theOneAudit() {
        assertThat(audits()).as("exactly one audit event for the edit").hasSize(1);
        AuditEvent event = audits().getFirst();
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.at()).isNotNull();
        assertThat(event.targetIds()).containsExactly("interface:" + FLOW, "rule_set:" + RULE_SET);
        assertThat(String.valueOf(event.before()) + event.after()).as("no table content in the audit")
                .doesNotContain("Germany");
        return event;
    }

    @Test
    void aCreatedRuleSetPublishesOneRuleSetCreatedEvent() {
        when(engine.check(anyString())).thenReturn(Optional.empty());
        when(ruleSets.flowExists(FLOW)).thenReturn(true);
        when(ruleSets.insert(any(), any(), any(), any(), any())).thenReturn(RULE_SET);

        service.create(FLOW.toString(), JSON.readTree("{\"kind\":\"LOOKUP\",\"name\":\"country codes\","
                + "\"content\":" + TABLE + "}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.RULE_SET_CREATED);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"kind\":\"LOOKUP\",\"name\":\"country codes\"}");
    }

    @Test
    void anUpdatedRuleSetPublishesOneRuleSetUpdatedEvent() {
        when(engine.check(anyString())).thenReturn(Optional.empty());
        when(ruleSets.find(UUID.fromString(RULE_SET))).thenReturn(Optional.of(
                new RuleSetRow(RULE_SET, FLOW.toString(), "VALIDATION", "old checks", TABLE)));
        when(ruleSets.update(any(), any(), any(), any())).thenReturn(1);

        service.update(RULE_SET, JSON.readTree("{\"name\":\"new checks\",\"content\":" + TABLE + "}"), ACTOR);

        AuditEvent event = theOneAudit();
        assertThat(event.action()).isEqualTo(ConfigAuditActions.RULE_SET_UPDATED);
        assertThat(event.before()).isEqualTo("{\"kind\":\"VALIDATION\",\"name\":\"old checks\"}");
        assertThat(event.after()).isEqualTo("{\"kind\":\"VALIDATION\",\"name\":\"new checks\"}");
    }

    @Test
    void aRefusedEditPublishesNothing() {
        when(engine.check(anyString())).thenReturn(Optional.of(
                new DecisionEngine.TableProblem(null, null, null, "bad cell")));
        when(ruleSets.flowExists(FLOW)).thenReturn(true);

        assertThatThrownBy(() -> service.create(FLOW.toString(), JSON.readTree(
                "{\"kind\":\"LOOKUP\",\"name\":\"n\",\"content\":" + TABLE + "}"), ACTOR))
                .isInstanceOf(RuleTableInvalidException.class);
        assertThat(audits()).isEmpty();
    }
}
