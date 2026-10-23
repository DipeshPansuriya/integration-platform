package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** T203 (T189-S1), no containers: the width query asks about every dangerous predefined role and account attribute. */
class JdbcDbPrivilegeProbeWidthSqlTest {

    private static final List<String> ROLES = List.of("pg_execute_server_program", "pg_write_server_files",
            "pg_read_server_files", "pg_signal_backend", "pg_database_owner", "pg_write_all_data", "pg_read_all_data");

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("every dangerous predefined role is read by name (a member, direct or inherited, is wide)")
    void everyDangerousPredefinedRoleIsAsked() {
        assertThat(JdbcDbPrivilegeProbe.WIDE_PREDEFINED_ROLES).containsExactlyElementsOf(ROLES);
        for (String role : ROLES) {
            assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL)
                    .contains("'" + role.toUpperCase() + "'")
                    .contains("rolname = '" + role + "'");
        }
        assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL).contains("pg_has_role(current_user, oid, 'member')");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("role attributes and create rights are read: CREATEROLE, CREATEDB, REPLICATION, DATABASE and any-schema CREATE")
    void attributesAndCreateRightsAreAsked() {
        assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL)
                .contains("'CREATEROLE'", "rolcreaterole")
                .contains("'CREATEDB'", "rolcreatedb")
                .contains("'REPLICATION'", "rolreplication")
                .contains("'DATABASE_CREATE'", "has_database_privilege(current_user, current_database(), 'CREATE')")
                .contains("'ANY_SCHEMA_CREATE'", "has_schema_privilege(current_user, n.oid, 'CREATE')")
                .contains("'OTHER_TABLE_PRIVILEGES'");
    }

    @Test
    @DisplayName("every name stays a statement parameter, and the number of parameters matches the slots the probe binds")
    void parametersMatchTheBoundSlots() {
        long placeholders = JdbcDbPrivilegeProbe.PRIVILEGES_SQL.chars().filter(c -> c == '?').count();
        assertThat(placeholders).isEqualTo(JdbcDbPrivilegeProbe.PARAMETER_COUNT);
        assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL).doesNotContain("\"");
    }

    @Test
    @DisplayName("the other-table read is bounded: one schema, relations only, never more than a count of the approved one")
    void theOtherTableReadIsBounded() {
        assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL)
                .contains("s.nspname = ?")
                .contains("c.relkind in ('r', 'p', 'v', 'm', 'f')")
                .contains("c.oid <> coalesce(to_regclass(?), 0::oid)");
    }
}
