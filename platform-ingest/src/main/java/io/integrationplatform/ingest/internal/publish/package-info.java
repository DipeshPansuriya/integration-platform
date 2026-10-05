/**
 * The in-memory implementation of the publish port (T-042): what every E2 lane tests the relay against before
 * Kafka (ADR-005); E8 replaces it in {@code platform-routing}. No vendor client may enter this package either -
 * it is the seam's stand-in, not its implementation.
 */
package io.integrationplatform.ingest.internal.publish;
