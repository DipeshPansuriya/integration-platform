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
        // walk up from the working directory (module under Maven, repo root under an IDE) to the folder that holds it
        for (Path dir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize(); dir != null;
                dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy/hostinger/audit").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("deploy/hostinger/audit/" + name + " not found above the working directory");
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

    @Test
    void theInterfaceVersionRollbackScriptEqualsTheMigrationHeaderBlock() throws IOException {
        String header = AuditContainerSupport.rollbackSql(
                AuditContainerSupport.migration("V*__interface_version_revoke_approval_columns.sql"));

        assertThat(normalised(script("rollback-V2_4_1530.sql"))).isEqualTo(normalised(header));
    }

    @Test
    void theOutboxInsertRollbackScriptEqualsTheMigrationHeaderBlock() throws IOException {
        String header = AuditContainerSupport.rollbackSql(
                AuditContainerSupport.migration("V*__audit_outbox_insert_without_relayed_at.sql"));

        assertThat(normalised(script("rollback-V2_4_1540.sql"))).isEqualTo(normalised(header));
    }
}
