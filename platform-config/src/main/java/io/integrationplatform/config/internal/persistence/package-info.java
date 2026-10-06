/**
 * The JDBC adapters of the configuration module (T-015/T-019/T-021). Every statement runs through
 * {@code TenantScopedRepository} (R08: the only JDBC entry points outside the reviewed tenancy crossings);
 * inserts take the {@code with ins as (insert ... returning) select} shape so the base's tenant predicate filters
 * even a write's answer, and every multi-table read is a single outer table with correlated subselects, because
 * the base appends an unqualified {@code tenant_id} predicate.
 *
 * <p>The repositories are plain final classes constructed here; the {@code TenantContext} arrives through an
 * {@code ObjectProvider} with a fail-closed fallback so a composition without the web identity chain (lane A's
 * {@code ConfigE3App}) still boots - the access itself then refuses, exactly like the stamping transaction
 * manager's documented behaviour.
 */
package io.integrationplatform.config.internal.persistence;
