package io.integrationplatform.e18;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * E18: a scenario written against the adapter CONTRACT that may fail only because a named lane's fix is not merged
 * yet. The test is never weakened and never skipped: it runs, and a failure is recorded as BLOCKED-BY (not FAILED)
 * with the exact assertion named here. The gate still counts BLOCKED as red - only a PASS closes the project.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface BlockedBy {

    /** The lane whose merge is awaited, for example {@code e16}. */
    String lane();

    /** The exact assertion that fails until it is merged. */
    String assertion();
}
