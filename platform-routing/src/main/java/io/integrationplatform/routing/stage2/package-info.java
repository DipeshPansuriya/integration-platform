/**
 * Routing stage 2 (E9 lane A's home package, amit-developer): the {@code DELIVERY_DUE} consumer that claims a
 * delivery and hands the send to the target's bounded bulkhead pool - never a target call on the partition thread
 * (T-177), Camel in-attempt redelivery for seconds with one {@code delivery_attempt} row per call (T-119,
 * retry layer 1), the bulkhead-full conditional deferral with the 1-2-4...30 s full-jitter backoff
 * (T-198, via {@code OrderedStateChange} and {@code BulkheadDeferrals}), and the {@code allowedSchemes} refusal
 * (T-120). Best-effort: one target failing never blocks or rolls back another (E9-C4).
 *
 * <p>E9 seam skeleton (arjun-fullstack) - the implementation arrives with lane A (A1 redelivery, A2 bulkhead);
 * other lanes never edit here.
 */
package io.integrationplatform.routing.stage2;