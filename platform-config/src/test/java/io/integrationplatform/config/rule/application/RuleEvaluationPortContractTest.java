package io.integrationplatform.config.rule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.rule.Jdm;
import io.integrationplatform.config.rule.adapter.out.ZenDecisionEngine;
import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleSetNotFoundException;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.Fingerprinted;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link RuleEvaluationPort} contract lanes A and C depend on, with the REAL engine (the repository is the only
 * stand-in): a VALIDATION rule set maps every matching collect row to a {@link RuleViolation}, in table order; ROUTING
 * and LOOKUP return the engine's output with no violations; an id the bound tenant cannot see throws
 * {@link RuleSetNotFoundException}.
 */
class RuleEvaluationPortContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ZenDecisionEngine engine;

    private static final UUID TENANT = UUID.randomUUID();

    private final JdbcRuleSetRepository repository = mock(JdbcRuleSetRepository.class);

    @BeforeAll
    static void engine() {
        engine = new ZenDecisionEngine();
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    private RuleEvaluationPort port() {
        return new RuleEvaluationService(repository, engine, () -> Optional.of(TENANT));
    }

    private UUID stored(String kind, String table) {
        UUID id = UUID.randomUUID();
        RuleSetRow row = new RuleSetRow(id.toString(), UUID.randomUUID().toString(), kind, kind + " set", table);
        when(repository.fingerprint(id)).thenReturn(Optional.of("fp"));
        when(repository.findFingerprinted(id)).thenReturn(Optional.of(new Fingerprinted(row, "fp")));
        return id;
    }

    @Test
    @DisplayName("VALIDATION: three matching rows give three violations, in table order")
    void validationRowsBecomeViolationsInTableOrder() {
        UUID id = stored("VALIDATION", Jdm.validationTable());

        RuleEvaluation evaluation = port().evaluate(id, JSON.readTree(Jdm.validationInput()));

        assertThat(evaluation.ruleSetId()).isEqualTo(id);
        assertThat(evaluation.kind()).isEqualTo("VALIDATION");
        assertThat(evaluation.violations()).containsExactly(
                new RuleViolation("amount", "Z_AMOUNT_TOO_HIGH", "amount must not exceed 100"),
                new RuleViolation("currency", "A_CURRENCY_UNKNOWN", "currency XXX is not accepted"),
                new RuleViolation("reference", "M_REFERENCE_REQUIRED", "reference is required"));
    }

    @Test
    @DisplayName("VALIDATION: a payload that matches no row has no violations")
    void validPayloadHasNoViolations() {
        UUID id = stored("VALIDATION", Jdm.validationTable());

        RuleEvaluation evaluation = port().evaluate(id,
                JSON.readTree("{\"amount\":10,\"currency\":\"EUR\",\"reference\":\"R-1\"}"));

        assertThat(evaluation.violations()).isEmpty();
    }

    @Test
    @DisplayName("ROUTING: the engine's output, no violations")
    void routingReturnsTheOutput() {
        UUID id = stored("ROUTING", Jdm.routingTable());

        RuleEvaluation evaluation = port().evaluate(id, JSON.readTree("{\"country\":\"NL\"}"));

        assertThat(evaluation.kind()).isEqualTo("ROUTING");
        assertThat(evaluation.output().path("target").asString()).isEqualTo("dst-eu");
        assertThat(evaluation.violations()).isEmpty();
    }

    @Test
    @DisplayName("LOOKUP: the engine's output, no violations")
    void lookupReturnsTheOutput() {
        UUID id = stored("LOOKUP", Jdm.lookupTable());

        RuleEvaluation evaluation = port().evaluate(id, JSON.readTree("{\"code\":\"DE\"}"));

        assertThat(evaluation.output().path("name").asString()).isEqualTo("Germany");
        assertThat(evaluation.violations()).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("An id the bound tenant cannot see throws RuleSetNotFoundException")
    void unknownRuleSetThrows() {
        UUID unknown = UUID.randomUUID();
        when(repository.fingerprint(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port().evaluate(unknown, JSON.readTree("{}")))
                .isInstanceOf(RuleSetNotFoundException.class)
                .hasMessageNotContaining(unknown.toString());
    }
}
