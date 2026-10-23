package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E16 F3 (design note task 11, Karan change 4), no containers: the catalogue question is fully parameterised (the
 * account, schema and table never enter the SQL text), a driver failure maps to the answer's reasons by SQL state,
 * the dial reuses the send path's account Properties (pinned factory, TLS, timeouts - never the password in the
 * URL), and a missing credential or account is refused, never probed passwordless.
 */
class JdbcDbPrivilegeProbeTest {

    private static final char[] SECRET = "pw".toCharArray();

    @Test
    @DisplayName("S2: the probe asks for the EFFECTIVE privileges (roles, PUBLIC, superuser, schema CREATE) - fully parameterised")
    void theProbeAsksForEffectivePrivileges() {
        assertThat(JdbcDbPrivilegeProbe.PRIVILEGES_SQL)
                .contains("has_table_privilege(current_user").contains("to_regclass(?)")
                .contains("has_any_column_privilege(current_user, to_regclass(?), 'UPDATE')")
                .contains("has_any_column_privilege(current_user, to_regclass(?), 'REFERENCES')")
                .contains("TABLE_MISSING")
                .contains("'INSERT'").contains("'SELECT'").contains("'UPDATE'").contains("'DELETE'")
                .contains("'TRUNCATE'").contains("'TRIGGER'").contains("rolbypassrls").contains("rolsuper").contains("pg_roles")
                .contains("has_schema_privilege(current_user, ?, 'CREATE')")
                .contains("pg_write_all_data").contains("pg_has_role")
                .doesNotContain("information_schema").doesNotContain("grantee")
                .doesNotContain("orders_in").doesNotContain("restricted");
        assertThat(JdbcDbPrivilegeProbe.DEFAULT_SCHEMA).isEqualTo("public");
    }

    @Test
    @DisplayName("S3: the account Properties pin currentSchema=public, for the send and for the probe alike")
    void theSchemaIsPinned() {
        Properties account = JdbcDbSender.accountProperties("restricted", SECRET, InetAddress.getLoopbackAddress(),
                "require", Duration.ofSeconds(5), null);

        assertThat(account.getProperty("currentSchema")).isEqualTo("public");
        assertThat(account.getProperty("currentSchema")).isEqualTo(JdbcDbPrivilegeProbe.DEFAULT_SCHEMA);
    }

    @Test
    @DisplayName("a driver failure maps by SQL state: 08 is unreachable, 28 a refused account, the rest an error")
    void aDriverFailureMapsBySqlState() {
        assertThat(JdbcDbPrivilegeProbe.unreachable(new SQLException("x", "08001"))).isTrue();
        assertThat(JdbcDbPrivilegeProbe.unreachable(new SQLException("x", "42501"))).isFalse();
        assertThat(JdbcDbPrivilegeProbe.refusedProbe(new SQLException("x", "28P01")).reason).isEqualTo("AUTH_REFUSED");
        assertThat(JdbcDbPrivilegeProbe.refusedProbe(new SQLException("x", "42501")).reason)
                .isEqualTo("DATABASE_ERROR");
        assertThat(JdbcDbPrivilegeProbe.refusedProbe(new SQLException("x", (String) null)).reason)
                .isEqualTo("DATABASE_ERROR");
    }

    @Test
    @DisplayName("the dial reuses the send path's account Properties: pinned factory, TLS, timeouts, no URL password")
    void theDialReusesTheSendPathAccountProperties() {
        Properties account = JdbcDbSender.accountProperties("restricted", SECRET,
                InetAddress.getLoopbackAddress(), "verify-full", Duration.ofSeconds(5),
                java.nio.file.Path.of("/ca/mock.pem"));

        assertThat(account.getProperty("user")).isEqualTo("restricted");
        assertThat(account.getProperty("sslrootcert")).isNotNull();
        assertThat(account.getProperty("socketFactory")).isEqualTo("io.integrationplatform.config.destination.PinnedSocketFactory");
        assertThat(account.getProperty("sslmode")).isEqualTo("verify-full");
        assertThat(account.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(account).containsKey("password");
        assertThat(JdbcDbSender.url(DbSettings.fromJson(
                "{\"host\":\"127.0.0.1\",\"port\":5432,\"database\":\"orders\"}")))
                .startsWith("jdbc:postgresql://127.0.0.1:5432/").doesNotContain("pw").doesNotContain("?");
    }

    @Test
    @Tag("negative")
    @DisplayName("no account in the settings: refused NO_ACCOUNT, never probed passwordless")
    void noAccountIsRefused() {
        JdbcDbPrivilegeProbe probe = new JdbcDbPrivilegeProbe(systemId -> Optional.of(SECRET.clone()), "require");

        assertThatThrownBy(() -> probe.probe(UUID.randomUUID(),
                DbSettings.fromJson("{\"host\":\"127.0.0.1\",\"port\":5432,\"database\":\"orders\"}"),
                InetAddress.getLoopbackAddress(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(DbPrivilegeProbe.ProbeRefused.class,
                        e -> assertThat(e.reason).isEqualTo("NO_ACCOUNT"));
    }

    @Test
    @Tag("negative")
    @DisplayName("no vault credential: refused NO_CREDENTIAL, never probed passwordless")
    void noCredentialIsRefused() {
        JdbcDbPrivilegeProbe probe = new JdbcDbPrivilegeProbe(systemId -> Optional.empty(), "require");

        assertThatThrownBy(() -> probe.probe(UUID.randomUUID(), DbSettings.fromJson(
                        "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\",\"user\":\"restricted\"}"),
                InetAddress.getLoopbackAddress(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(DbPrivilegeProbe.ProbeRefused.class,
                        e -> assertThat(e.reason).isEqualTo("NO_CREDENTIAL"));
    }

    @Test
    @Tag("negative")
    @DisplayName("a target that never answers is an IOException - and the credential copy is wiped even then")
    void anUnansweringTargetIsUnreachableAndThePasswordIsWiped() throws Exception {
        char[] password = SECRET.clone();
        JdbcDbPrivilegeProbe probe = new JdbcDbPrivilegeProbe(systemId -> Optional.of(password), "require");

        assertThatThrownBy(() -> probe.probe(UUID.randomUUID(), DbSettings.fromJson(
                        "{\"host\":\"127.0.0.1\",\"port\":2,\"database\":\"orders\",\"user\":\"restricted\"}"),
                InetAddress.getLoopbackAddress(), Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class);

        assertThat(password).containsOnly('\0');
    }
}