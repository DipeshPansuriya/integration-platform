/**
 * The configuration module's API surface (E3 lane C, T-015/T-019/T-021): the admin APIs a configurator drives -
 * systems, contracts and flows - and their lifecycle (draft, submit, approve, publish) over the schema lane B
 * deployed.
 *
 * <p>Hexagonal shape, like the delivery and ingest modules: {@code <area>.adapter.in} holds the thin
 * {@code @RestController} seam (HTTP only), {@code <area>.application} holds the rules as plain services whose
 * methods open the transaction (R11 - the tenant stamp belongs to the transaction, so a lifecycle walk never runs
 * half-stamped), and {@code internal.persistence} holds the JDBC adapters that extend the tenancy module's
 * {@code TenantScopedRepository} - the only SQL sites in the module, wired by {@code ConfigPersistenceConfiguration}.
 */
package io.integrationplatform.config;
