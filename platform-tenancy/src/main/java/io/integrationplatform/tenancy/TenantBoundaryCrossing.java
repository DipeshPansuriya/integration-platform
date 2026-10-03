package io.integrationplatform.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the few classes allowed to run SQL that carries no tenant predicate because they cross the tenant boundary on
 * purpose: calls to the definer functions of architecture.md section 5, control T5 ({@code ipaas_login_bootstrap},
 * later {@code ipaas_client_bootstrap}, {@code ipaas_work_tenant}, {@code ipaas_active_tenants}) and the
 * transaction-local {@code set_config('app.tenant_id', ...)} stamp (ADR-007 Decision 6).
 *
 * <p>E1 seam: lane A's ArchUnit rule (T-003) exempts only classes carrying this annotation and pins the exact list of
 * them, so a new crossing fails the build until it is reviewed; lane C (T-157) marks its bootstrap adapter and its
 * stamping class with it. Row-level security still applies to every statement such a class runs.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantBoundaryCrossing {

    /** Why this class may cross the boundary: the definer function or ADR decision it implements. */
    String value();
}
