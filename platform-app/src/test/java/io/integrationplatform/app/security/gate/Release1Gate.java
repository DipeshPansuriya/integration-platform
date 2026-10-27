package io.integrationplatform.app.security.gate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T-147 (E15-F1-S2) Release 1 security gate roll-up. Test-scope only: it decides the gate from recorded case
 * results, it never runs an attack.
 *
 * <ul>
 *   <li>Scenario 1: each of the four areas needs at least one recorded verdict (PASS or FAIL) with evidence.
 *       BLOCKED and TBD rows are not verdicts; they neither fail nor satisfy the gate.</li>
 *   <li>Scenario 2: an open Critical or High FAIL keeps Release 1 open; its case id is returned so the finding
 *       goes to the project owner directly.</li>
 * </ul>
 */
public final class Release1Gate {

    private static final Pattern CASE_ID = Pattern.compile("S[1-8]-A[1-9]");
    private static final Pattern TRACKER_ITEM = Pattern.compile("T\\d+");

    private Release1Gate() {}

    /** The four review areas of E15-F1-S2. */
    public enum Area { TENANT_ISOLATION, IDENTITY, OUTBOUND_DESTINATION, CREDENTIAL_STORAGE }

    /** Verdict column of the evidence row (test plan section 5). */
    public enum Verdict { PASS, FAIL, BLOCKED, TBD }

    /** Severity column; {@code NONE} for every verdict except FAIL. */
    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, NONE;

        boolean blocksRelease() {
            return this == CRITICAL || this == HIGH;
        }
    }

    /** One case of the 54-case matrix, the area it rolls up to and the tracker item it waits on. */
    public record GateCase(String id, Area area, String waitingOn) {
        public GateCase {
            require(id != null && CASE_ID.matcher(id).matches(), "case id must be S<1-8>-A<1-9>: " + id);
            Objects.requireNonNull(area, "area");
            require(waitingOn != null && TRACKER_ITEM.matcher(waitingOn).matches(),
                    id + " must name the tracker item it waits on (T<n>): " + waitingOn);
        }
    }

    /** One evidence row: the verdict, its severity and the {@code _reports/} file that holds the raw output. */
    public record CaseResult(String caseId, Verdict verdict, Severity severity, String evidencePath) {
        public CaseResult {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(severity, "severity");
            require(evidencePath != null && !evidencePath.isBlank()
                            && evidencePath.replace('\\', '/').contains("_reports/"),
                    caseId + ": evidence must be a file under _reports/");
            if (verdict == Verdict.FAIL) {
                require(severity != Severity.NONE, caseId + ": a FAIL needs a severity");
            } else {
                require(severity == Severity.NONE, caseId + ": only a FAIL carries a severity");
            }
        }
    }

    /** The roll-up: whether Release 1 may close, what blocks it, and what goes to the project owner directly. */
    public record Decision(boolean releaseMayClose, Set<Area> areasWithoutVerdict, List<String> openCriticalOrHigh) {
        public Decision {
            areasWithoutVerdict = Set.copyOf(areasWithoutVerdict);
            openCriticalOrHigh = List.copyOf(openCriticalOrHigh);
        }
    }

    public static Decision decide(List<GateCase> cases, List<CaseResult> results) {
        Map<String, GateCase> byId = new HashMap<>();
        for (GateCase c : cases) {
            require(byId.putIfAbsent(c.id(), c) == null, "duplicate case " + c.id());
        }
        Set<String> seen = new HashSet<>();
        EnumSet<Area> withVerdict = EnumSet.noneOf(Area.class);
        List<String> blocking = new ArrayList<>();
        for (CaseResult r : results) {
            GateCase c = byId.get(r.caseId());
            require(c != null, "result for a case not in the plan: " + r.caseId());
            require(seen.add(r.caseId()), "two results for one case: " + r.caseId());
            if (r.verdict() == Verdict.PASS || r.verdict() == Verdict.FAIL) {
                withVerdict.add(c.area());
            }
            if (r.verdict() == Verdict.FAIL && r.severity().blocksRelease()) {
                blocking.add(r.caseId());
            }
        }
        EnumSet<Area> missing = EnumSet.complementOf(withVerdict);
        return new Decision(missing.isEmpty() && blocking.isEmpty(), missing, blocking);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
