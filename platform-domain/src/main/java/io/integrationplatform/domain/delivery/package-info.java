/**
 * The Delivery state register and the outcome classes (ADR-010 sections 5 and 6).
 *
 * <p>E2 seam (arjun-fullstack): {@link io.integrationplatform.domain.delivery.DeliveryState} and
 * {@link io.integrationplatform.domain.delivery.OutcomeClass} are fixed before the lanes start, because lane B (retry
 * policy, T-090) and lane C (state machine and repository, T-011 to T-193) both build on them. A lane that needs a
 * change to either reports it to arjun-fullstack instead of editing it. Lane C adds the Delivery entity and its guarded
 * transitions as new types in this package.
 */
package io.integrationplatform.domain.delivery;
