package io.integrationplatform.config.rule.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.parity.ParityFixture.Tenant;
import io.integrationplatform.config.rule.parity.planted.evaluator.FirstViolationOnlyEvaluator;
import io.integrationplatform.config.rule.parity.planted.evaluator.PlantedEvaluatorWiring;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T-029 (E6-F3-S1) R02 planted violation for the parity assertion itself:
 *
 * <pre>
 * &#64;negative
 * Scenario: A different engine is caught
 *   Given the test panel wired to anything other than the runtime evaluation port
 *   When the parity test runs
 *   Then it fails
 * </pre>
 *
 * The same composed app, except that the evaluate endpoint is wired to {@link FirstViolationOnlyEvaluator} - a planted
 * evaluator that reports only the first violation (the first-error-only defect E6-C2 forbids). The runtime side stays
 * the real {@code ruleEvaluationPort} bean. The unchanged parity assertion must fail and name the disagreeing samples.
 */
@Tag("container")
@Tag("negative")
@SpringBootTest(classes = {ParitySuiteApp.class, PlantedEvaluatorWiring.class},
        webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DifferentEngineParityContainerTest {

    @LocalServerPort
    private int port;

    /** The runtime's own port, by its production bean name (the planted evaluator is the primary one). */
    @Autowired
    @Qualifier("ruleEvaluationPort")
    private RuleEvaluationPort runtime;

    @Autowired
    private RuleEvaluationPort panelWiring;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        ParityFixture.wire(registry);
    }

    @Test
    @DisplayName("A different engine is caught: the panel wired to another evaluator fails the parity test")
    void aDifferentEngineIsCaught() {
        assertThat(panelWiring).as("the planted evaluator is what the panel endpoint receives")
                .isInstanceOf(FirstViolationOnlyEvaluator.class);
        assertThat(runtime).as("the runtime side is the real port").isNotInstanceOf(FirstViolationOnlyEvaluator.class);
        Tenant tenant = ParityFixture.tenant();
        UUID ruleSetId = ParityRuns.storeRuleSet(port, tenant);

        assertThatThrownBy(() -> Parity.assertSameAnswer(Parity.samples(),
                sample -> ParityRuns.panelVerdict(port, tenant, ruleSetId, sample),
                sample -> ParityRuns.runtimeVerdict(runtime, tenant, ruleSetId, sample),
                Parity.expectedVerdicts()))
                .isInstanceOf(AssertionError.class)
                .satisfies(failure -> System.out.println("PLANTED-DIFFERENT-ENGINE parity failure:\n"
                        + failure.getMessage()))
                .hasMessageContaining("panel == runtime: 16 of 20")
                .hasMessageContaining("s09").hasMessageContaining("s10").hasMessageContaining("s16")
                .hasMessageContaining("s19");
    }
}
