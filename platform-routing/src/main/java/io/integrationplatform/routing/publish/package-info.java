/**
 * The Kafka implementation of the ingest {@code PublishPort} (E8-F1-S1, T-110): records keyed with
 * SHA-256 of (tenant, interface, business key) so same-key messages stay in order, the row's stored
 * {@code traceparent} carried as the record's only header (ADR-011 Decision 7 - ids only, no payload,
 * no tenant id on the wire). Plain kafka-clients via camel-kafka (R12) - never spring-kafka.
 */
package io.integrationplatform.routing.publish;