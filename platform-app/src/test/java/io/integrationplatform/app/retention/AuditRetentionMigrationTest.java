package io.integrationplatform.app.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Row327 T118 (E12-F2-S3, T-135): the audit purge migration's static shape, without Docker - the behaviour (only the
 * partition older than 7 years is dropped) is {@code AuditRetentionContainerTest}'s. Found by its description, not
 * its version, so renumbering at the seam keeps this guard. The purge is a partition drop and never a row delete
 * (architecture.md, R15); it runs as the migration owner with a fixed search path and a lock timeout; it is granted to
 * nobody - not the application role, which runs no DDL (architecture.md control T6, ADR-011 Decision 8, ADR-016) -
 * until the maintenance role of T-135b exists; it takes no argument, so no caller can move the cutoff; and its cutoff
 * is the configured audit figure.
 * Not tagged container (R03).
 */
class AuditRetentionMigrationTest {

    private static final String FUNCTION = "public.audit_purge_expired()";

    private static String migration() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__audit_retention.sql");
        assertThat(found).as("exactly one audit retention migration").hasSize(1);
        return found[0].getContentAsString(StandardCharsets.UTF_8);
    }

    /** The statements only: every {@code --} comment removed. */
    private static String statements() throws IOException {
        return migration().replaceAll("--[^\\n]*", "");
    }

    @Test
    void thePurgeDropsPartitionsAsTheOwnerWithAFixedSearchPathAndALockTimeout() throws IOException {
        String sql = statements();

        assertThat(sql).contains("CREATE OR REPLACE FUNCTION " + FUNCTION)
                .contains("SECURITY DEFINER")
                .contains("SET search_path = pg_catalog, pg_temp")
                .contains("SET lock_timeout = '5s'")
                .contains("DROP TABLE")
                .contains("REVOKE ALL ON FUNCTION " + FUNCTION + " FROM PUBLIC");
    }

    @Test
    @Tag("negative")
    void thePurgeIsGrantedToNobodyAndNeverToTheApplicationRole() throws IOException {
        String sql = statements();

        assertThat(Pattern.compile("\\bGRANT\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find())
                .as("no GRANT at all until the T-135b maintenance role exists")
                .isFalse();
        assertThat(sql).as("the application role runs no DDL (architecture.md T6)").doesNotContain("ipaas_app");
    }

    @Test
    void theCutoffIsTheConfiguredAuditRetention() throws IOException {
        int years = RetentionPolicy.AGREED.audit().getYears();

        assertThat(years).isEqualTo(7);
        assertThat(statements()).contains("interval '" + years + " years'");
    }

    @Test
    @Tag("negative")
    void thePurgeNeverDeletesOrTruncatesRows() throws IOException {
        assertThat(Pattern.compile("\\b(DELETE|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE).matcher(statements()).find())
                .as("audit is purged by partition drop only - no DELETE or TRUNCATE statement")
                .isFalse();
    }
}
