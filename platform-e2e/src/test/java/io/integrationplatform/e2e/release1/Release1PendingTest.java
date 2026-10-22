package io.integrationplatform.e2e.release1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Row 323 T139/T140, no Docker: the gating of the Release 1 scenarios. A scenario whose code is not on main is
 * DISABLED with a reason naming the T-item and the one flag to flip; an unknown item or a TBD name fails closed.
 */
class Release1PendingTest {

    private static final Map<String, Boolean> FLAGS = Map.of("T95", true, "T98", false, "T143", false);

    @Test
    void everyItemAScenarioWaitsOnHasAFlag() {
        for (Release1Scenario scenario : Release1Scenario.values()) {
            assertThat(scenario.waitsOn()).as("%s waits on at least one item", scenario.id()).isNotEmpty();
            assertThat(Release1Pending.ON_MAIN).as("%s items", scenario.id()).containsKeys(scenario.waitsOn());
        }
    }

    @Test
    void itemsOnMainGateNothing() {
        assertThat(Release1Pending.waitsOn(FLAGS, "T95")).isEmpty();
    }

    @Test
    void missingItemsAreAllNamedWithTheFlagToFlip() {
        Optional<String> reason = Release1Pending.waitsOn(FLAGS, "T95", "T98", "T143");

        assertThat(reason).hasValueSatisfying(text -> assertThat(text)
                .contains("waits on T98, T143 (not on main)")
                .contains("Release1Pending.ON_MAIN.T98"));
    }

    @Test
    void anUnknownItemFailsClosed() {
        assertThatThrownBy(() -> Release1Pending.waitsOn(FLAGS, "T999"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("T999");
    }

    @Test
    void namingNoItemIsRefused() {
        assertThatThrownBy(() -> Release1Pending.waitsOn(FLAGS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFlagListCannotBeChangedAtRunTime() {
        assertThatThrownBy(() -> Release1Pending.ON_MAIN.put("T98", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theRealFlagsHaveEveryItemOnMainSoNoScenarioIsSilentlyGated() {
        // re-measured 22-Oct against main b6106af2: the MQ adapter (T98) and the compose e2e profile (T143) landed;
        // a scenario is no longer disabled - it runs, or fails loudly on a missing e2e env / TBD name (E2eNames)
        assertThat(Release1Pending.ON_MAIN).containsEntry("T98", true).containsEntry("T143", true);
        for (Release1Scenario scenario : Release1Scenario.values()) {
            assertThat(Release1Pending.waitsOn(scenario.waitsOn())).as(scenario.id()).isEmpty();
        }
    }

    // --- the JUnit condition ---

    @Test
    void anElementWithoutWaitsOnIsEnabled() throws NoSuchMethodException {
        ConditionEvaluationResult result = evaluate(Fixture.class.getDeclaredMethod("ungated"));

        assertThat(result.isDisabled()).isFalse();
    }

    @Test
    void anElementWaitingOnAnUnmergedItemIsDisabledWithTheItemNamed() throws NoSuchMethodException {
        // explicit flags: every real flag is true since the 22-Oct re-measure, so the gate is proven on a false one
        ConditionEvaluationResult result = WaitsOnCondition.evaluate(
                Optional.of(Fixture.class.getDeclaredMethod("gated").getAnnotation(WaitsOn.class)), Map.of("T98", false));

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValueSatisfying(text -> assertThat(text).contains("T98"));
    }

    @Test
    void theConditionUsesTheGivenFlags() throws NoSuchMethodException {
        Method gated = Fixture.class.getDeclaredMethod("gated");

        assertThat(WaitsOnCondition.evaluate(Optional.of(gated.getAnnotation(WaitsOn.class)),
                Map.of("T98", true)).isDisabled()).isFalse();
    }

    @Test
    void aClassLevelWaitsOnGatesTheWholeClass() {
        ConditionEvaluationResult result = WaitsOnCondition.evaluate(
                Optional.of(GatedFixture.class.getAnnotation(WaitsOn.class)), Map.of("T143", false));

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValueSatisfying(text -> assertThat(text).contains("T143"));
    }

    // --- the name registry ---

    @Test
    void aResolvedNameIsReturned() {
        assertThat(E2eNames.nameOf(Map.of("intakeApi", "/api/v1/x"), "intakeApi")).isEqualTo("/api/v1/x");
    }

    @Test
    void aTbdNameFailsWithTheItemItWaitsFor() {
        assertThatThrownBy(() -> E2eNames.nameOf(Map.of("replayApi", "TBD(T111)"), "replayApi"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TBD(T111)")
                .hasMessageContaining("E2eNames");
    }

    @Test
    void everyNameProvableFromSourceIsKnownNotTbd() {
        for (String key : new String[] {"intakeApi", "timelineApi", "failuresApi", "bulkIntakeApi", "replayApi",
                "dailyReportApi", "tenantBCodePrefix"}) {
            assertThat(E2eNames.nameOf(key)).as(key).isNotBlank().doesNotContain("TBD(");
        }
        assertThat(E2eNames.nameOf("bulkIntakeApi")).isEqualTo("/api/v1/interfaces/{interfaceCode}/messages:bulk");
        assertThat(E2eNames.nameOf("replayApi")).isEqualTo("/api/v1/admin/messages/deliveries/{deliveryId}/replay");
        assertThat(E2eNames.nameOf("dailyReportApi")).isEqualTo("/api/v1/reports/delivery?day={day}");
        assertThat(E2eNames.nameOf("tenantBCodePrefix")).isEqualTo("e2e-seed-b-");
    }

    @Test
    void theOneNameNotInSourceStaysANamedTbdWithItsReason() {
        // the MQ queue is a registry setting the scenario's seed creates (MqSettings.java:7), not a server constant
        assertThat(E2eNames.NAMES.get("mqTargetQueue")).startsWith("TBD(T98").contains("MqSettings");
        assertThatThrownBy(() -> E2eNames.nameOf("mqTargetQueue")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("T98");
    }

    @Test
    void anUnknownNameFailsClosed() {
        assertThatThrownBy(() -> E2eNames.nameOf(Map.of(), "nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBaseUrlWaitsOnTheComposeProfileWhenNotGiven() {
        assertThatThrownBy(() -> E2eNames.baseUrl(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2e.baseUrl").hasMessageContaining("T143");
        assertThat(E2eNames.baseUrl("http://127.0.0.1:8080/")).isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    void theBaseUrlIsLoopbackOnlySoARunCanNeverReachProduction() {
        assertThatThrownBy(() -> E2eNames.baseUrl("https://integration.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
    }

    private static ConditionEvaluationResult evaluate(AnnotatedElement element) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.of(element));
        return new WaitsOnCondition().evaluateExecutionCondition(context);
    }

    static class Fixture {
        void ungated() {
        }

        @WaitsOn("T98")
        void gated() {
        }
    }

    @WaitsOn("T143")
    static class GatedFixture {
    }
}
