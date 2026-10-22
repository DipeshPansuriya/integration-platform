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
    void theRealFlagsGateEveryScenarioThatNeedsAnUnmergedItem() {
        // re-measured 22-Oct against main ecef544b: the MQ adapter (T98) and the compose e2e profile (T143) are on main;
        // bulk intake (T115) and the daily report (T120) are not
        assertThat(Release1Pending.ON_MAIN).containsEntry("T98", true).containsEntry("T143", true)
                .containsEntry("T115", false).containsEntry("T120", false);
        for (Release1Scenario scenario : Release1Scenario.values()) {
            boolean needsUnmergedItem = scenario == Release1Scenario.SC02 || scenario == Release1Scenario.SC06
                    || scenario == Release1Scenario.SC08; // T115 (bulk intake, same-key ordering), T120 (daily report)
            assertThat(Release1Pending.waitsOn(scenario.waitsOn()).isPresent()).as(scenario.id())
                    .isEqualTo(needsUnmergedItem);
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
        ConditionEvaluationResult result = evaluate(Fixture.class.getDeclaredMethod("gated"));

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValueSatisfying(text -> assertThat(text).contains("T115"));
    }

    @Test
    void theConditionUsesTheGivenFlags() throws NoSuchMethodException {
        Method gated = Fixture.class.getDeclaredMethod("gated");

        assertThat(WaitsOnCondition.evaluate(Optional.of(gated.getAnnotation(WaitsOn.class)),
                Map.of("T115", true)).isDisabled()).isFalse();
    }

    @Test
    void aClassLevelWaitsOnGatesTheWholeClass() {
        ConditionEvaluationResult result = evaluate(GatedFixture.class);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValueSatisfying(text -> assertThat(text).contains("T120"));
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

        @WaitsOn("T115")
        void gated() {
        }
    }

    @WaitsOn("T120")
    static class GatedFixture {
    }
}
