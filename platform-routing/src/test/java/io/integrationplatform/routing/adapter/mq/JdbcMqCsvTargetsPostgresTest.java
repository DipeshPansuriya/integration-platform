package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.routing.adapter.mq.MqCsvTargets.MqCsvTarget;
import io.integrationplatform.routing.stage2.RoutingTestDatabase;
import io.integrationplatform.routing.stage2.RoutingTestDatabase.TenantTx;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-125 (E10-S2): the endpoint row's target read against real PostgreSQL with the RLS reality of
 * {@link RoutingTestDatabase} - its own tenant's stored MQ system settings become the send target, and another
 * tenant's (or a non-MQ system's) endpoint row reads empty.
 */
@Tag("container")
class JdbcMqCsvTargetsPostgresTest {

    @Test
    void theStoredRowBecomesTheTarget() {
        RoutingTestDatabase db = RoutingTestDatabase.get();
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String settings = "{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"t125.csv\","
                + "\"user\":\"mq-conn\",\"contract\":" + MqCsvSendWiringTest.STORED_CONTRACT + "}";

        try (HikariDataSource pool = db.appPool(2)) {
            TenantTx tx = new TenantTx(pool);
            plant(tx, "'MQ'", settings, systemId, endpointId);

            AtomicReference<Optional<MqCsvTarget>> read = new AtomicReference<>();
            tx.inTenantTx(RoutingTestDatabase.TENANT_A, () -> read.set(new JdbcMqCsvTargets(tx.jdbcClient(),
                    RoutingTestDatabase.tenant(RoutingTestDatabase.TENANT_A),
                    id -> Optional.of("mq-pass".toCharArray())).byEndpointId(endpointId)));

            MqCsvTarget target = read.get().orElseThrow();
            assertThat(target.settings()).isEqualTo(new MqSettings("broker.example", 61616, "t125.csv"));
            assertThat(target.user()).isEqualTo("mq-conn");
            assertThat(target.password()).isEqualTo("mq-pass".toCharArray());
            assertThat(target.contract().delimiter()).isEqualTo(';');
        }
    }

    @Test
    void aNonMqSystemEndpointReadsEmpty() {
        RoutingTestDatabase db = RoutingTestDatabase.get();
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String settings = "{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"t125.csv\","
                + "\"user\":\"mq-conn\",\"contract\":" + MqCsvSendWiringTest.STORED_CONTRACT + "}";

        try (HikariDataSource pool = db.appPool(2)) {
            TenantTx tx = new TenantTx(pool);
            plant(tx, "'DB'", settings, systemId, endpointId);

            AtomicReference<Optional<MqCsvTarget>> read = new AtomicReference<>();
            tx.inTenantTx(RoutingTestDatabase.TENANT_A, () -> read.set(new JdbcMqCsvTargets(tx.jdbcClient(),
                    RoutingTestDatabase.tenant(RoutingTestDatabase.TENANT_A),
                    id -> Optional.of("mq-pass".toCharArray())).byEndpointId(endpointId)));

            assertThat(read.get()).isEmpty();
        }
    }

    @Test
    void anotherTenantSeesNothing() {
        RoutingTestDatabase db = RoutingTestDatabase.get();
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        String settings = "{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"t125.csv\","
                + "\"user\":\"mq-conn\",\"contract\":" + MqCsvSendWiringTest.STORED_CONTRACT + "}";
        UUID otherTenant = UUID.randomUUID();

        try (HikariDataSource pool = db.appPool(2)) {
            TenantTx tx = new TenantTx(pool);
            plant(tx, "'MQ'", settings, systemId, endpointId);

            AtomicReference<Optional<MqCsvTarget>> read = new AtomicReference<>();
            tx.inTenantTx(otherTenant, () -> read.set(new JdbcMqCsvTargets(tx.jdbcClient(),
                    RoutingTestDatabase.tenant(otherTenant),
                    id -> Optional.of("mq-pass".toCharArray())).byEndpointId(endpointId)));

            assertThat(read.get()).isEmpty();
        }
    }

    private static void plant(TenantTx tx, String type, String settings, UUID systemId, UUID endpointId) {
        tx.inTenantTx(RoutingTestDatabase.TENANT_A, () -> tx.jdbcClient()
                .sql("insert into public.system (id, tenant_id, code, name, direction, type, settings)"
                        + " values (:id::uuid, :tenant::uuid, :code, 'An MQ system', 'TARGET', "
                        + type + ", :settings::jsonb)")
                .params(Map.of("id", systemId.toString(), "tenant", RoutingTestDatabase.TENANT_A.toString(),
                        "code", "mq-csv-" + systemId, "settings", settings)).update());
        tx.inTenantTx(RoutingTestDatabase.TENANT_A, () -> tx.jdbcClient()
                .sql("insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                        + " http_method, auth_method, timeout_ms)"
                        + " values (:id::uuid, :tenant::uuid, :system::uuid, :code, 'The CSV endpoint',"
                        + " 'http://ignored.example', 'POST', 'NO_AUTH', 1000)")
                .params(Map.of("id", endpointId.toString(), "tenant", RoutingTestDatabase.TENANT_A.toString(),
                        "system", systemId.toString(), "code", "mq-csv-ep-" + endpointId)).update());
    }
}