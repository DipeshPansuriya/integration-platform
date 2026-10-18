package io.integrationplatform.e2e.release1;

/**
 * Row 323 T140 (E15-F1-S1): the nine Release 1 scenarios, each with the pass criterion from the scenario plan
 * ({@code _reports/row327-e15-scenario-plan-20261017.md}) and the T-items its code waits on.
 */
public enum Release1Scenario {

    SC01("SC-01", "The reference example: one call, two targets, two contract versions",
            "1 delivery per target (API v1.0, MQ-CSV v2.0); 0 new Flyway rows; 0 restarts", "T98", "T143"),
    SC02("SC-02", "Bulk with invalid records",
            "10 results: 7 accepted each with its own tracking id; 3 rejected with ALL their reasons",
            "T115", "T98", "T143"),
    SC03("SC-03", "Retry then success",
            "503,503,200: DELIVERED after 3 append-only attempts via RETRY_WAIT; target B unaffected",
            "T99", "T98", "T143"),
    SC04("SC-04", "Dead-letter then replay with pinned versions",
            "DEAD_LETTERED; newer versions published; clock +14d; approver replays the SAME delivery row with pinned "
                    + "v1.0/v2.0 shapes; full attempt history kept",
            "T111", "T96", "T144", "T98", "T143"),
    SC05("SC-05", "New contract version with zero deploys",
            "import v2.1 -> target B receives v2.1; image digest, start time and Flyway count unchanged",
            "T98", "T143"),
    SC06("SC-06", "Same-key ordering",
            "per key arrival order = acceptance order; a HELD K1 blocks only later K1; K2 flows on",
            "T115", "T112", "T110", "T143"),
    SC07("SC-07", "Cross-tenant denial (end-to-end leg; the security cases are T141)",
            "tenant B sees 0 rows of tenant A; refusals are 403/404", "T157", "T143"),
    SC08("SC-08", "Report counts equal the delivery rows",
            "success, failure-with-reason, retries, retried-then-succeeded and still-stuck counts = SQL counts",
            "T120", "T99", "T111", "T98", "T143"),
    SC09("SC-09", "Stage timings present",
            "every message has INTAKE/ROUTE/TRANSFORM/DELIVER times and durations; a failed one marks its stage",
            "T105", "T110", "T112", "T98", "T143");

    private final String id;
    private final String title;
    private final String criterion;
    private final String[] waitsOn;

    Release1Scenario(String id, String title, String criterion, String... waitsOn) {
        this.id = id;
        this.title = title;
        this.criterion = criterion;
        this.waitsOn = waitsOn;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String criterion() {
        return criterion;
    }

    public String[] waitsOn() {
        return waitsOn.clone();
    }
}
