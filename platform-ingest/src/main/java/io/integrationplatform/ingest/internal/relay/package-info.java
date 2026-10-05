/**
 * The outbox relay (T-008, E2-F1): a scanning job over {@code platform-tenancy}'s active tenants claiming
 * unpublished {@code outbox} rows per key bucket under {@code FOR UPDATE SKIP LOCKED}, publishing them through the
 * frozen {@link io.integrationplatform.ingest.port.PublishPort} only, and marking {@code published_at} just after a
 * positive acknowledge (ADR-005 Decisions 11 and 12, ADR-011 Decision 8). No vendor client may be imported here -
 * the ArchUnit rule of the matching test package fails the build on one (T-041, E2-F1-S2).
 */
package io.integrationplatform.ingest.internal.relay;
