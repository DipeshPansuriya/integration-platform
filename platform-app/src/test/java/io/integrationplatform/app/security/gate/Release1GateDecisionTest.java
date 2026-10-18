package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.app.security.gate.Release1Gate.Area;
import io.integrationplatform.app.security.gate.Release1Gate.CaseResult;
import io.integrationplatform.app.security.gate.Release1Gate.Decision;
import io.integrationplatform.app.security.gate.Release1Gate.GateCase;
import io.integrationplatform.app.security.gate.Release1Gate.Severity;
import io.integrationplatform.app.security.gate.Release1Gate.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-147 (E15-F1-S2): the two gate scenarios, decided from recorded evidence rows. Pure unit test, no containers. */
class Release1GateDecisionTest {

    private static final List<GateCase> FOUR_AREAS = List.of(
            new GateCase("S2-A1", Area.TENANT_ISOLATION, "T157"),
            new GateCase("S1-A1", Area.IDENTITY, "T139"),
            new GateCase("S6-A1", Area.OUTBOUND_DESTINATION, "T68"),
            new GateCase("S8-A1", Area.CREDENTIAL_STORAGE, "T100"));

    private static CaseResult pass(String id) {
        return new CaseResult(id, Verdict.PASS, Severity.NONE, "_reports/t147-" + id.toLowerCase() + ".md");
    }

    @Test
    void scenario1_everyAreaWithAVerdictAndEvidence_letsReleaseClose() {
        Decision d = Release1Gate.decide(FOUR_AREAS,
                List.of(pass("S2-A1"), pass("S1-A1"), pass("S6-A1"), pass("S8-A1")));

        assertThat(d.releaseMayClose()).isTrue();
        assertThat(d.areasWithoutVerdict()).isEmpty();
        assertThat(d.openCriticalOrHigh()).isEmpty();
    }

    @Test
    void scenario1_anAreaWithOnlyBlockedOrTbd_hasNoVerdict_andKeepsTheGateShut() {
        Decision d = Release1Gate.decide(FOUR_AREAS, List.of(pass("S2-A1"), pass("S1-A1"), pass("S6-A1"),
                new CaseResult("S8-A1", Verdict.BLOCKED, Severity.NONE, "_reports/t147-s8-a1.md")));

        assertThat(d.releaseMayClose()).isFalse();
        assertThat(d.areasWithoutVerdict()).containsExactly(Area.CREDENTIAL_STORAGE);
    }

    @Test
    void scenario1_anAreaWithNoResultAtAll_keepsTheGateShut() {
        Decision d = Release1Gate.decide(FOUR_AREAS, List.of(pass("S2-A1"), pass("S1-A1"), pass("S8-A1")));

        assertThat(d.releaseMayClose()).isFalse();
        assertThat(d.areasWithoutVerdict()).containsExactly(Area.OUTBOUND_DESTINATION);
    }

    @Test
    void scenario2_anOpenCriticalOrHigh_blocksRelease_andIsNamedForDipesh() {
        for (Severity blocking : List.of(Severity.CRITICAL, Severity.HIGH)) {
            Decision d = Release1Gate.decide(FOUR_AREAS, List.of(pass("S1-A1"), pass("S6-A1"), pass("S8-A1"),
                    new CaseResult("S2-A1", Verdict.FAIL, blocking, "_reports/t147-s2-a1.md")));

            assertThat(d.releaseMayClose()).as("%s FAIL", blocking).isFalse();
            assertThat(d.openCriticalOrHigh()).containsExactly("S2-A1");
            assertThat(d.areasWithoutVerdict()).as("a FAIL is still a recorded verdict").isEmpty();
        }
    }

    @Test
    void aMediumOrLowFail_isRecordedOpen_butDoesNotBlock() {
        for (Severity minor : List.of(Severity.MEDIUM, Severity.LOW)) {
            Decision d = Release1Gate.decide(FOUR_AREAS, List.of(pass("S1-A1"), pass("S6-A1"), pass("S8-A1"),
                    new CaseResult("S2-A1", Verdict.FAIL, minor, "_reports/t147-s2-a1.md")));

            assertThat(d.releaseMayClose()).as("%s FAIL", minor).isTrue();
            assertThat(d.openCriticalOrHigh()).isEmpty();
        }
    }

    @Test
    void aResultWithoutEvidenceUnderReports_isRefused() {
        assertThatThrownBy(() -> new CaseResult("S2-A1", Verdict.PASS, Severity.NONE, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseResult("S2-A1", Verdict.PASS, Severity.NONE, "C:/tmp/out.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailNeedsASeverity_andOnlyAFailMayCarryOne() {
        assertThatThrownBy(() -> new CaseResult("S2-A1", Verdict.FAIL, Severity.NONE, "_reports/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseResult("S2-A1", Verdict.PASS, Severity.HIGH, "_reports/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResultForAnUnknownCase_orTwoResultsForOneCase_isRefused() {
        assertThatThrownBy(() -> Release1Gate.decide(FOUR_AREAS, List.of(pass("S9-A1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Release1Gate.decide(FOUR_AREAS, List.of(pass("S2-A1"), pass("S2-A1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCaseMustHaveAPlanIdAndNameTheTrackerItemItWaitsOn() {
        assertThatThrownBy(() -> new GateCase("S2-A1", Area.TENANT_ISOLATION, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateCase("S2-A1", Area.TENANT_ISOLATION, "soon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateCase("X2-A1", Area.TENANT_ISOLATION, "T157"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
