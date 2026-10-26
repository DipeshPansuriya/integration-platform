package io.integrationplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Karan P-1 follow-ups (T64 + T65, row 278 review): the connection pool and the transaction timeout are
 * pinned EXPLICITLY in {@code platform-app/src/main/resources/application.yml}, so the pool-exhaustion incident
 * (row306 smoke: HikariPool-1 total=10 active=10, requests hung 30 s then failed - _reports/row306-demo-smoke-20261012.txt:92-95,
 * CWE-400/770 availability risk) cannot come back through a silently-changed or silently-defaulted setting.
 * Not tagged container: runs in a bare {@code ./mvnw test} without Docker - the test loads the application.yml
 * resource and binds the keys, it never starts the application.
 */
class PoolSafetyDefaultsTest {

    private List<PropertySource<?>> settings;

    @BeforeEach
    void loadApplicationYml() throws IOException {
        settings = new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"));
    }

    private String valueOf(String key) {
        return settings.stream().map(s -> s.getProperty(key)).filter(v -> v != null).findFirst()
                .map(String::valueOf).orElse(null);
    }

    @Test
    void thePoolIsExplicitlySizedAgainstThePostgresConnectionHeadroom() {
        // Postgres default max_connections=100 leaves the headroom; T181 sized the pool at 20 per instance for N
        // consumer loops per stage (the throughput-ceiling design note) - explicit, so a boot upgrade cannot change
        // it silently.
        assertThat(valueOf("spring.datasource.hikari.maximum-pool-size")).as("max pool size pinned in application.yml")
                .isEqualTo("20");
    }

    @Test
    void fourInstancesAtThePoolSizeStayInsideThePostgresConnectionLimit() {
        // T181: the design caps the sizing for 4 instances; max_connections=100 keeps 20 for the owner login,
        // sessions and admin tooling. More instances or a bigger pool is a Dipesh decision, not a quiet edit.
        int pool = Integer.parseInt(valueOf("spring.datasource.hikari.maximum-pool-size"));
        assertThat(pool * 4).as("4 instances x pool size").isLessThanOrEqualTo(80);
    }

    @Test
    void theBorrowWaitIsExplicitAndBounded() {
        // explicit 30 s borrow patience - the incident's request hang; NOT the unbounded wait a misconfigured pool
        // would give.
        assertThat(valueOf("spring.datasource.hikari.connection-timeout"))
                .as("connection (borrow) timeout pinned in application.yml").isEqualTo("30000");
    }

    @Test
    void aLeakedConnectionReportsAtSixtySeconds() {
        // Karan's P-1 figure: leak-detection-threshold 60000 ms - the leak prints long before the borrow patience
        // starves the pool.
        assertThat(valueOf("spring.datasource.hikari.leak-detection-threshold"))
                .as("leak detection threshold pinned in application.yml").isEqualTo("60000");
    }

    @Test
    void everyTransactionIsBoundedByADefaultTimeout() {
        // T65: no platform-default unbounded transaction - a long-borrow stall surfaces as a bounded timeout
        // (matched to the 30 s borrow patience above, so a stuck start never holds a pool slot past it).
        assertThat(valueOf("spring.transaction.default-timeout"))
                .as("default transaction timeout pinned in application.yml").isEqualTo("30");
    }
}