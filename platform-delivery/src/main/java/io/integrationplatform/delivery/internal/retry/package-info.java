/**
 * Retry timing as configuration and the outcome-to-state-move recorder (T-090): the ADR-010 section 4 layer
 * figures (layer-1 doubling waits behind the delay-only cap; layer-2 full-jitter exponential backoff with a
 * ceiling, {@code Retry-After} honoured) with the section 6 stale-claim formula, and the recorder that applies
 * them through lane C's {@link io.integrationplatform.delivery.port.DeliveryRetryStore} contract only.
 */
package io.integrationplatform.delivery.internal.retry;
