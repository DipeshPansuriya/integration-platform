/**
 * Data plane intake - common API, contract validation, intake transaction and outbox; never uses
 * Camel (rule R12). Public surface: this package plus {@code port} and {@code event}; everything
 * else goes under {@code io.integrationplatform.ingest.internal} (ADR-001 item 5).
 */
package io.integrationplatform.ingest;
