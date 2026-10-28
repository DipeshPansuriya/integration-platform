package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Row 544 (review R1): the rollback scripts an operator runs on the VPS ({@code deploy/hostinger/audit/rollback-*.sql})
 * are the SQL documented in the headers of the migrations between {@code -- ROLLBACK-BEGIN} and {@code -- ROLLBACK-END}
 * - the same text {@link AuditOptionARollbackContainerTest} executes. If a header changes without the script (or the
 * reverse), this fails. Not tagged container: no Docker needed.
 */
class RollbackScriptsTest {

    private static String normalised(String sql) {
        return sql.replace("\r\n", "\n").stripTrailing();
    }

    private static String script(String name) throws IOException {
        Path folder = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize().getParent()
                .resolve("deploy/hostinger/audit");
        return Files.readString(folder.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void theAuditRollbackScriptEqualsTheMigrationHeaderBlock() throws IOException {
        String header = AuditContainerSupport.rollbackSql(
                AuditContainerSupport.migration("V*__audit_relay_move_and_lockdown.sql"));

        assertThat(normalised(script("rollback-V2_4_1490.sql"))).isEqualTo(normalised(header));
    }

    @Test
    void theApprovalRollbackScriptEqualsTheMigrationHeaderBlock() throws IOException {
        String header = AuditContainerSupport.rollbackSql(
                AuditContainerSupport.migration("V*__approval_actor_from_session.sql"));

        assertThat(normalised(script("rollback-V2_4_1500.sql"))).isEqualTo(normalised(header));
    }
}
