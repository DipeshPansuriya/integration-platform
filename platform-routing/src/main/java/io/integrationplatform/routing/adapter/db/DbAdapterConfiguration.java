package io.integrationplatform.routing.adapter.db;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.SocketDialer;
import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-149 wiring: the DB adapter's beans - the "DB" test-connection check (the checks-map shape the MQ adapter
 * answers too) and the production target call. Gated by its own property so no existing context changes until
 * the lead wires the two register() seams and flips the gate the way {@code KafkaAdapterConfiguration} is wired.
 *
 * <p>E16 security: {@code platform.config.destinations.db-allowed-ports} (default 5432) lists the database ports a
 * target may use; {@code platform.config.destinations.db-sslmode} (default {@code require}; require, verify-ca or
 * verify-full - validated at start) is the TLS mode of a target that names none; {@code db-sslrootcert-dir} and
 * {@code db-sslrootcert} (a file name under that directory) give the driver the CA for verify-ca / verify-full.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_DB_TARGET_ENABLED")
public class DbAdapterConfiguration {

    @Bean
    DbTargets jdbcDbTargets(JdbcClient jdbcClient, TenantContext tenantContext,
            ObjectProvider<SendTimeCredentials> vault) {
        SendTimeCredentials credentials = vault.getIfAvailable();
        if (credentials == null) {
            return null; // no credential slice composed: the reader is not wired (nothing calls it yet either)
        }
        return new JdbcDbTargets(jdbcClient, tenantContext, endpointId -> {
            try (OpenedCredentials opened = credentials.open(endpointId)) {
                return opened.value(JdbcDbTargets.DB_ACCOUNT_PASSWORD_FIELD).map(char[]::clone);
                // copy: close() zeroes the original
            }
        });
    }

    /**
     * T-149 with E16 F3 (task 11, Karan change 4): the DB test-connection answers the account's real privileges on
     * the exact target table when the credential slice is composed (its own short read-only transaction - the
     * T-104 rule that no platform connection is held while a destination answers is kept); otherwise it stays the
     * socket-only reachability answer it always was.
     */
    @Bean
    MqConnectionCheck dbConnectionCheck(DestinationGuard guard, SocketDialer dialer, JdbcClient jdbcClient,
            TenantContext tenantContext, ObjectProvider<SendTimeCredentials> vault,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions,
            @Value("${platform.config.destinations.db-allowed-ports:5432}") java.util.List<Integer> allowedPorts,
            @Value("${platform.config.destinations.db-sslmode:require}") String sslmode) {
        DbTestConnection connection = connection(guard, dialer, jdbcClient, tenantContext, vault, transactions,
                allowedPorts, sslmode);
        return connection::test;
    }

    /**
     * T178a: the approval's width question, answered by the SAME probe as the test-connection; with no vault slice
     * composed the check refuses every approval (fail closed). B30: routing's own port - the composition root hands
     * it to config's approval (routing uses no config application type).
     */
    @Bean
    DbAccountWidth dbAccountWidth(DestinationGuard guard,
            SocketDialer dialer, JdbcClient jdbcClient, TenantContext tenantContext,
            ObjectProvider<SendTimeCredentials> vault,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions,
            @Value("${platform.config.destinations.db-allowed-ports:5432}") java.util.List<Integer> allowedPorts,
            @Value("${platform.config.destinations.db-sslmode:require}") String sslmode) {
        DbTestConnection connection = connection(guard, dialer, jdbcClient, tenantContext, vault, transactions,
                allowedPorts, sslmode);
        return connection::accountWidthRefusal;
    }

    private static DbTestConnection connection(DestinationGuard guard, SocketDialer dialer, JdbcClient jdbcClient,
            TenantContext tenantContext, ObjectProvider<SendTimeCredentials> vault,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions,
            java.util.List<Integer> allowedPorts, String sslmode) {
        DbBrokerProbe probe = new SocketDbBrokerProbe(guard, dialer);
        // R6: test-connection applies the SAME port allow-list the send path applies
        DbPrivilegeProbe privileges = null;
        SendTimeCredentials slice = vault.getIfAvailable();
        org.springframework.transaction.PlatformTransactionManager manager = transactions.getIfAvailable();
        if (slice != null && manager != null) {
            org.springframework.transaction.support.TransactionTemplate readOnly =
                    new org.springframework.transaction.support.TransactionTemplate(manager);
            readOnly.setReadOnly(true);
            privileges = new JdbcDbPrivilegeProbe(
                    new JdbcDbSystemCredentials(jdbcClient, tenantContext, slice, readOnly), sslmode);
        }
        return new DbTestConnection(guard, probe, privileges, allowedPorts, Duration.ofSeconds(5));
    }

    @Bean
    DbSender dbSender(@Value("${platform.config.destinations.db-sslmode:require}") String sslmode,
            @Value("${platform.config.destinations.db-sslrootcert-dir:}") String rootCertDir,
            @Value("${platform.config.destinations.db-sslrootcert:}") String rootCert,
            org.springframework.core.env.Environment environment) {
        // R8: a typo or an unencrypted mode stops the start instead of failing open; R3: the operator's CA file
        String mode = DbTlsPolicy.requireSslmode(sslmode, environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of("test")));
        return new JdbcDbSender(Duration.ofSeconds(5), mode, DbTlsPolicy.rootCert(rootCertDir, rootCert));
    }

    @Bean
    DbOutbound dbOutbound(DbTargets targets, ObjectProvider<DbRows> rows) {
        DbRows rowsPort = rows.getIfAvailable();
        if (rowsPort == null) {
            return null; // the rows port composes with the transform wiring (lead integration, the MqCsvRows seam)
        }
        return new DbTargetOutbound(targets, rowsPort);
    }

    /** E16 F3: the send-time and publish-time reads of the tenant's approved tables (one JDBC adapter, both ports). */
    @Bean
    JdbcApprovedTables jdbcApprovedTables(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcApprovedTables(jdbcClient, tenantContext);
    }

    /** E16 F3 (task 9): the publish-time read of the mapped columns not in the route's approved target contract. */
    @Bean
    JdbcDbRouteColumns jdbcDbRouteColumns(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcDbRouteColumns(jdbcClient, tenantContext);
    }

    /** E16 F3 (tasks 9 and 10): an unapproved DB table, or a mapped column outside the approved contract, refuses. */
    @Bean
    DbTablePublishListener dbTablePublishListener(JdbcApprovedTables approved, JdbcDbRouteColumns missingColumns) {
        return new DbTablePublishListener(approved, missingColumns);
    }

    @Bean
    DbTargetCall dbTargetCall(ApprovedTables approvedTables, DbOutbound outbound, DbSender sender, DestinationGuard guard,
            @Value("${platform.config.destinations.db-allowed-ports:5432}") java.util.List<Integer> allowedPorts,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManager,
            ObjectProvider<CircuitBreakerRegistry> breakers, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        return compose(approvedTables, outbound, sender, guard, allowedPorts, transactionManager.getIfAvailable(),
                breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * T175 (T71 for DB): the platform reads of a send - settings, credential, mapped rows ({@code resolve}) and the F3
     * approved-table check - each run in their own short tenant-stamped transaction that is over before the
     * destination is dialled. The target insert dials the target's own connection ({@code JdbcDbSender}) and holds no
     * pooled application connection while it waits. The approval stays a hard gate before the insert: a lookup that
     * cannot answer throws (TRANSIENT), it is never read as approved.
     */
    static DbTargetCall compose(ApprovedTables approvedTables, DbOutbound outbound, DbSender sender,
            DestinationGuard guard, java.util.List<Integer> allowedPorts,
            org.springframework.transaction.PlatformTransactionManager transactions,
            CircuitBreakerRegistry breakers, MeterRegistry meters, ObservationRegistry observations) {
        if (transactions != null) {
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
            DbOutbound reads = outbound;
            ApprovedTables approval = approvedTables;
            outbound = send -> tx.execute(status -> reads.resolve(send));
            org.springframework.transaction.support.TransactionTemplate readOnly =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
            readOnly.setReadOnly(true);
            approvedTables = (endpointId, table) ->
                    Boolean.TRUE.equals(readOnly.execute(status -> approval.isApproved(endpointId, table)));
        }
        return new DbTargetCall(approvedTables, outbound, sender, guard, allowedPorts, breakers, meters, observations);
    }
}
