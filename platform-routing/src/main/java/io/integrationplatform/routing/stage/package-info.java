/**
 * The stage-1 record consumer base (E8-F1-S1, T-111/T-181): a consumer loads the row and its tenant
 * through the RLS-protected work path - never from a Kafka header or record value (E8-C4) - and a
 * transient database error pauses the partition instead of committing the offset (no record skipped,
 * none sent to poison; per ADR fix m-817272, B4).
 */
package io.integrationplatform.routing.stage;