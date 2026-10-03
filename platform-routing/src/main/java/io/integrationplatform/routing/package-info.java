/**
 * Data plane routing - the only module that uses Apache Camel (rule R12, ADR-010).
 * Public surface: this package plus {@code port} and {@code event}; everything else goes under
 * {@code io.integrationplatform.routing.internal} (ADR-001 item 5).
 */
package io.integrationplatform.routing;
