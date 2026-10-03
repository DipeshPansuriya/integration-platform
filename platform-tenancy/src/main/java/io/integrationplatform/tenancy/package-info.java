/**
 * platform-tenancy - the tenant context, per-transaction stamping, the bootstrap resolvers, the OIDC resource server and
 * role checks (architecture.md section 2). Public surface: this package and {@code port}; everything else lives under
 * {@code internal} (ADR-001 item 5).
 *
 * <p>The tenant comes only from the authenticated principal through our own directory - never from a request body,
 * header, query parameter, path or Kafka record (architecture.md section 5, control T1; ADR-007 Decision 2).
 */
package io.integrationplatform.tenancy;
