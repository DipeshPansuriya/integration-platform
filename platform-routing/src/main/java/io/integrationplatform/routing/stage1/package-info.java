/**
 * Routing stage 1 (E9 lane C's home package, rohit-developer): the {@code message.accepted} consumer that fans a
 * message out to its matching routes - recipient list with conditions through {@code RuleEvaluationPort}
 * (T-117), per-route delivery insertion with the idempotent {@code (message_id, route_id, created_at)} conflict
 * key (T-178, ADR-014), the same-key hold including an earlier unrouted message (T-165, ADR-011 Decision 6) and
 * the one shared lock order ({@code OrderedStateChange}, T-197). Nothing outside this package may write a
 * delivery row; best-effort isolation means one failing route never stops the fan-out (E9-C4,
 * proven in platform-app by {@code e9/isolation/BestEffortIsolationContainerTest}).
 *
 * <p>E9 seam skeleton (arjun-fullstack) - the implementation arrives with lane C; other lanes never edit here.
 */
package io.integrationplatform.routing.stage1;