package io.integrationplatform.e9.isolation;

import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.SocketDialer;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import io.integrationplatform.PlatformTestWiring;
import java.util.function.Supplier;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The E9 suites' test network (the e11 pattern): {@code *.e9.test} resolves to the documentation address
 * {@value #PINNED_TEXT}, which the destination guard accepts and pins; the dialer connects only that address and
 * port to the in-process {@link TargetStub} and refuses everything else, so no test reaches a real host.
 */
@TestConfiguration(proxyBeanMethods = false)
public class E9TestNetwork {

    static final String PINNED_TEXT = "203.0.113.10";
    /** Test config only: the allowed-hosts entry the suites register; the guard itself is unchanged. */
    public static final String ALLOWED_HOSTS = "*.e9.test";

    @Bean
    @Primary
    HostResolver e9TargetNames() {
        return host -> {
            if (host.endsWith(".e9.test")) {
                return List.of(InetAddress.getByName(PINNED_TEXT)); // a literal, never a DNS query
            }
            throw new UnknownHostException(host);
        };
    }

    @Bean
    @Primary
    SocketDialer e9TargetDialer() {
        return (address, connectTimeoutMillis) -> {
            if (!address.getAddress().getHostAddress().equals(PINNED_TEXT)
                    || address.getPort() != TargetStub.PINNED_PORT) {
                throw new ConnectException("the test network has no route to " + address);
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), TargetStub.port()),
                    connectTimeoutMillis);
            return socket;
        };
    }

    /** The three ADR-010 topics (auto-create is off on the broker). */
    public static void createTheThreeTopics(String bootstrapServers) {
        createTheThreeTopics(bootstrapServers, 3);
    }

    /** As above with {@code partitions} per topic - 1 puts every record on one partition thread. */
    public static void createTheThreeTopics(String bootstrapServers, int partitions) {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers))) {
            admin.createTopics(List.of("platform.message.accepted", "platform.delivery.due", "platform.poison")
                    .stream().map(topic -> new NewTopic(topic, partitions, (short) 1)).toList())
                    .all().get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new IllegalStateException("the three ADR-010 topics could not be created: " + e.getMessage(), e);
            }
        }
    }

    /** Grants ipaas_app to the restricted login right after Flyway, before db-scheduler's startup reconcile. */
    public static Callback grantAppRoleAfterMigrate(PostgreSQLContainer postgres, Supplier<PlatformTestWiring.RestrictedLogin> login) {
        return new Callback() {
            @Override public boolean supports(Event event, Context context) { return event == Event.AFTER_MIGRATE; }
            @Override public boolean canHandleInTransaction(Event event, Context context) { return false; }
            @Override public String getCallbackName() { return "e9GrantAppRole"; }
            @Override public void handle(Event event, Context context) {
                try {
                    PlatformTestWiring.grantApplicationRole(postgres, login.get());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
