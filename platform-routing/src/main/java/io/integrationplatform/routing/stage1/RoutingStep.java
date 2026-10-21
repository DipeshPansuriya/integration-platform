package io.integrationplatform.routing.stage1;

/** The named routing steps of E9-F1-S3 the per-message budget is measured against (T-122 AC-1). */
public enum RoutingStep {
    /** Read the accepted record's message row (and its state guard). */
    LOAD,

    /** Read the pinned interface snapshot and select the ACTIVE routes. */
    EVALUATE_CONDITIONS,

    /** Insert the pinned deliveries and move the message ACCEPTED -&gt; ROUTED. */
    CREATE_DELIVERIES
}
