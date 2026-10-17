package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.flow.api.InterfacePublished;
import io.integrationplatform.routing.templates.RouteInstanceFactoryTest.FakeRouteConfigReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row 278 T106, Varun N2 / Kavita F14 (ADR-010 section 3 amendment, AC-1): the publish listener's rollback contract
 * with a REAL transaction around it, so the listener runs at BEFORE_COMMIT (not through fallbackExecution). A
 * refused row rolls the publish back, and neither a refused publish nor a failed commit leaves a live or cached
 * route behind: publish validates, it never builds.
 */
@Tag("e9-t116")
class InterfacePublishedRouteRollbackTest {

    private static final UUID TENANT = seed("tenant-a");
    private static final UUID INTERFACE_ID = seed("iface-1");
    private static final UUID ENDPOINT_HTTPS = seed("endpoint-https");
    private static final UUID ENDPOINT_HTTPS_2 = seed("endpoint-https-2");
    private static final UUID ENDPOINT_EXEC = seed("endpoint-exec");

    private DefaultCamelContext camel;
    private FakeRouteConfigReader rows;
    private RouteInstanceFactory factory;
    private RecordingTransactionManager transactions;
    private AnnotationConfigApplicationContext spring;

    @BeforeEach
    void aRunningSystem() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();
        rows = new FakeRouteConfigReader();
        factory = new RouteInstanceFactory(rows, camel, List.of(new RouteInstanceFactoryTest.RecordingTemplate("API")),
                Set.of("https"), Duration.ofMinutes(30), () -> Instant.parse("2026-10-17T12:00:00Z"));
        transactions = new RecordingTransactionManager();

        spring = new AnnotationConfigApplicationContext();
        spring.registerBean(RouteInstanceFactory.class, () -> factory);
        spring.registerBean("transactionManager", RecordingTransactionManager.class, () -> transactions);
        spring.register(TransactionalEvents.class, RouteTemplatesConfiguration.class);
        spring.refresh();
    }

    @AfterEach
    void stopTheSystem() throws Exception {
        spring.close();
        camel.stop();
    }

    @Test
    void aRefusedRowRollsThePublishBackAndLeavesNoLiveOrCachedRoute() {
        rows.add(new RouteConfigRow(seed("route-https"), ENDPOINT_HTTPS, "API", "https://target-a.example/v1",
                null, null));
        rows.add(new RouteConfigRow(seed("route-exec"), ENDPOINT_EXEC, "API", "exec:/bin/echo", null, null));

        assertThatThrownBy(this::publishInsideATransaction)
                .as("the refused exec: row fails the publish at BEFORE_COMMIT")
                .isInstanceOf(RouteBuildRefusedException.class);

        assertThat(transactions.rolledBack).as("the publish transaction was rolled back").isEqualTo(1);
        assertThat(transactions.committed).as("nothing committed").isZero();
        assertThat(camel.getRoutes()).as("a refused publish leaves no live Camel route (F14)").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_HTTPS)).as("and no cached instance for the valid row").isEmpty();
    }

    @Test
    void aFailedCommitAfterTheListenerLeavesNoLiveOrCachedRoute() {
        rows.add(new RouteConfigRow(seed("route-https"), ENDPOINT_HTTPS, "API", "https://target-a.example/v1",
                null, null));
        rows.add(new RouteConfigRow(seed("route-https-2"), ENDPOINT_HTTPS_2, "API", "https://target-b.example/v1",
                null, null));
        transactions.failCommit = true;

        assertThatThrownBy(this::publishInsideATransaction).as("the commit itself fails")
                .isInstanceOf(TransactionSystemException.class);

        assertThat(transactions.committed).isZero();
        assertThat(camel.getRoutes()).as("a publish whose commit failed leaves no live Camel route (F14)").isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_HTTPS)).isEmpty();
        assertThat(factory.instanceOf(ENDPOINT_HTTPS_2)).isEmpty();
    }

    private void publishInsideATransaction() {
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> spring.publishEvent(new InterfacePublished(TENANT, INTERFACE_ID, 3)));
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Registers the transactional event listener factory, so the listener binds to BEFORE_COMMIT. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionalEvents {
    }

    /** No database: a transaction manager that records its outcome and can be told to fail the commit. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        int committed;
        int rolledBack;
        boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("the commit failed");
            }
            committed++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack++;
        }
    }
}
