package io.integrationplatform.app.security.gate;

import java.util.List;
import java.util.Set;

/**
 * T-147 (E15-F1-S2) Release 1 security gate roll-up. Test-scope only: it decides the gate from recorded case
 * results, it never runs an attack. Skeleton for the RED commit.
 */
public final class Release1Gate {

    private Release1Gate() {}

    /** The four review areas of E15-F1-S2. */
    public enum Area { TENANT_ISOLATION, IDENTITY, OUTBOUND_DESTINATION, CREDENTIAL_STORAGE }

    /** Verdict column of the evidence row (test plan section 5). */
    public enum Verdict { PASS, FAIL, BLOCKED, TBD }

    /** Severity column; {@code NONE} for every verdict except FAIL. */
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, NONE }

    /** One case of the 54-case matrix, the area it rolls up to and the tracker item it waits on. */
    public record GateCase(String id, Area area, String waitingOn) {}

    /** One evidence row: the verdict, its severity and the {@code _reports/} file that holds the raw output. */
    public record CaseResult(String caseId, Verdict verdict, Severity severity, String evidencePath) {}

    /** The roll-up: whether Release 1 may close, what blocks it, and what goes to Dipesh directly. */
    public record Decision(boolean releaseMayClose, Set<Area> areasWithoutVerdict, List<String> openCriticalOrHigh) {}

    public static Decision decide(List<GateCase> cases, List<CaseResult> results) {
        throw new UnsupportedOperationException("T-147 gate roll-up not built yet");
    }
}
