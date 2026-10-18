package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.flow.application.FlowAdminService;
import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import io.integrationplatform.routing.stage2.RoutingTestDatabase;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-116 container leg (row 278 T68, sanjay-developer; E9-F1-S1 "Publish creates the route at runtime", ADR-010
 * section 3): the REAL config publish path ({@link FlowAdminService#publish} through its transaction proxy, the real
 * {@link JdbcFlowRepository}) writes the interface's route rows on PostgreSQL, announces the publish, and the routing
 * module's listener validates each ACTIVE route row - read by the real {@link JdbcRouteConfigReader} inside the
 * publish transaction - and builds nothing (ADR-010 s3 amendment, row 278 T106); stage 2 then builds each row lazily
 * as a live route in the Camel context that was started before the publish. A new target published on the live
 * interface adds exactly its route on first use; the instances already live are re-used, never rebuilt.
 * 0 deploys, 0 restarts: the one context is never stopped, started or reloaded.
 *
 * <p>Not exercised here, by design: the T-190 mapping pin check (its own suite, T190MappingPinContainerTest) is a
 * no-op stand-in, and the per-target-type template is the test's - production templates arrive with E10 (ADR-015).
 */
@Tag("container")
class InterfacePublishedRoutesFromConfigRowsContainerTest {

    private static final UUID TENANT = RoutingTestDatabase.TENANT_A;

    private static HikariDataSource pool;
    private static JdbcClient jdbc;
    private static PlatformTransactionManager stamped;

    private final AtomicInteger contextStartsOrStops = new AtomicInteger();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private DefaultCamelContext camel;
    private AnnotationConfigApplicationContext spring;
    private RouteInstanceFactory factory;
    private JdbcRouteConfigReader reader;

    @BeforeAll
    static void database() {
        pool = RoutingTestDatabase.get().appPool(4);
        jdbc = JdbcClient.create(pool);
        stamped = stampingManager(pool, RoutingTestDatabase.tenant(TENANT));
    }

    @AfterAll
    static void closePool() {
        pool.close();
    }

    @BeforeEach
    void aRunningSystem() throws Exception {
        camel = new DefaultCamelContext();
        camel.start(); // the system is running before anything is published
        camel.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
            @Override
            public void notify(CamelEvent event) {
                switch (event.getType()) {
                    case CamelContextStarting, CamelContextStopping, CamelContextReloading -> contextStartsOrStops
                            .incrementAndGet();
                    default -> { }
                }
            }
        });
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow row) {
                return exchange -> sent.add(row.endpointUri() + "<-" + exchange.getMessage().getBody(String.class));
            }
        };
        reader = new JdbcRouteConfigReader(jdbc, RoutingTestDatabase.tenant(TENANT));
        factory = new RouteInstanceFactory(reader, camel, List.of(api), Set.of("https"), Duration.ofMinutes(30), Instant::now);

        spring = new AnnotationConfigApplicationContext();
        spring.registerBean(RouteInstanceFactory.class, () -> factory);
        spring.register(ConfigPublishPath.class, RouteTemplatesConfiguration.class);
        spring.refresh();
    }

    @AfterEach
    void stopTheSystem() throws Exception {
        spring.close();
        camel.stop();
    }

    @Test
    void publishingAnInterfaceAndThenANewTargetRunsTheirRoutesFromTheConfigurationRowsWithoutARestart() {
        Seed seed = new Seed();
        UUID endpointA = seed.targetEndpoint("https://target-a.example/v1");
        UUID endpointB = seed.targetEndpoint("https://target-b.example/v1");
        UUID endpointPaused = seed.targetEndpoint("https://target-paused.example/v1");
        RouteRow routeA = seed.draftRoute(endpointA, 1, "ACTIVE");
        RouteRow routeB = seed.draftRoute(endpointB, 2, "ACTIVE");
        RouteRow routePaused = seed.draftRoute(endpointPaused, 3, "PAUSED");
        MappingRow mappingA = seed.boundMapping(routeA);
        MappingRow mappingB = seed.boundMapping(routeB);
        seed.approvedVersion(1, List.of(routeA, routeB, routePaused), List.of(mappingA, mappingB));
        assertThat(camel.getRoutes()).as("nothing is instantiated before the publish").isEmpty();

        spring.getBean(FlowAdminService.class).publish(seed.interfaceId.toString());

        assertThat(camel.getRoutes()).as("AC-1: the publish validates the rows and builds nothing").isEmpty();
        ensureActiveRoutes(seed.interfaceId); // stage 2 builds each ACTIVE row's instance lazily on first use
        assertThat(routeIds()).as("one live route instance per ACTIVE route row; the paused row is not instantiated")
                .containsExactlyInAnyOrder(camelRouteId(routeA), camelRouteId(routeB));
        RouteInstance liveA = factory.instanceOf(endpointA).orElseThrow();
        assertThat(liveA.pinnedMappingVersionId()).as("the mapping version pinned from the published rows")
                .hasToString(mappingA.mappingVersionId());
        assertThat(liveA.pinnedContractVersionId()).as("the target contract version pinned from the published rows")
                .hasToString(mappingA.targetContractVersionId());
        assertThat(factory.instanceOf(endpointB).orElseThrow().pinnedMappingVersionId())
                .hasToString(mappingB.mappingVersionId());
        send(endpointA, "m-1");
        send(endpointB, "m-2");
        assertThat(sent).as("each route runs the send built from its row's endpoint URI")
                .containsExactly("https://target-a.example/v1<-m-1", "https://target-b.example/v1<-m-2");
        assertThat(seed.interfaceStatus()).as("the publish committed").isEqualTo("PUBLISHED");

        // publishing a new target on the live interface: version 2 adds a route to target D
        UUID endpointD = seed.targetEndpoint("https://target-d.example/v1");
        RouteRow routeD = new RouteRow(UUID.randomUUID().toString(), 4, null, false, "ACTIVE", endpointD.toString());
        seed.approvedVersion(2, List.of(routeA, routeB, routePaused, routeD), List.of(mappingA, mappingB));

        spring.getBean(FlowAdminService.class).publish(seed.interfaceId.toString());

        assertThat(routeIds()).as("AC-1: the republish adds no route")
                .containsExactlyInAnyOrder(camelRouteId(routeA), camelRouteId(routeB));
        ensureActiveRoutes(seed.interfaceId);
        assertThat(routeIds()).as("the new target's route joined the running context")
                .containsExactlyInAnyOrder(camelRouteId(routeA), camelRouteId(routeB), camelRouteId(routeD));
        assertThat(factory.instanceOf(endpointA).orElseThrow()).as("a live instance is re-used, never rebuilt")
                .isSameAs(liveA);
        send(endpointD, "m-3");
        assertThat(sent).last().isEqualTo("https://target-d.example/v1<-m-3");
        assertThat(camel.isStarted()).as("the same running context").isTrue();
        assertThat(contextStartsOrStops).as("0 restarts: the context was never stopped or started again").hasValue(0);
    }

    /**
     * Row 278 T106, Varun N2 / Kavita F14 (ADR-010 s3 amendment, AC-1): the REAL publish of an interface whose route
     * rows are one https: target and one exec: target is refused at BEFORE_COMMIT and rolled back - the interface's
     * status and published-version pointer are unchanged, and no live or cached route is left behind.
     */
    @Test
    void aPublishWithARefusedSchemeRowRollsBackAndLeavesNoRoute() {
        Seed seed = new Seed();
        UUID endpointHttps = seed.targetEndpoint("https://target-a.example/v1");
        UUID endpointExec = seed.targetEndpoint("exec:/bin/echo");
        RouteRow routeHttps = seed.draftRoute(endpointHttps, 1, "ACTIVE");
        RouteRow routeExec = seed.draftRoute(endpointExec, 2, "ACTIVE");
        seed.approvedVersion(1, List.of(routeHttps, routeExec),
                List.of(seed.boundMapping(routeHttps), seed.boundMapping(routeExec)));
        List<String> pointerBefore = seed.publishPointer();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> spring.getBean(FlowAdminService.class).publish(seed.interfaceId.toString()));

        assertThat(thrown).as("the publish is refused").isNotNull();
        RouteBuildRefusedException refused = null;
        for (Throwable t = thrown; t != null && refused == null; t = t.getCause()) {
            if (t instanceof RouteBuildRefusedException r) {
                refused = r;
            }
        }
        assertThat(refused).as("the refusal is (or is caused by) RouteBuildRefusedException: " + thrown).isNotNull();
        assertThat(refused.getMessage()).as("the refusal names the scheme").contains("'exec'");
        assertThat(seed.publishPointer()).as("status and published-version pointer rolled back")
                .isEqualTo(pointerBefore);
        assertThat(camel.getRoutes()).as("a refused publish leaves no live Camel route (F14)").isEmpty();
        assertThat(factory.instanceOf(endpointHttps)).as("and no cached instance for the https row").isEmpty();
    }

    /** Stage 2's lazy build: each ACTIVE row read in a tenant-stamped transaction, then ensured. */
    private void ensureActiveRoutes(UUID interfaceId) {
        new TransactionTemplate(stamped).execute(status -> reader.activeRoutes(TENANT, interfaceId))
                .forEach(factory::ensure);
    }

    private List<String> routeIds() {
        // F16: a live id is the configuration route plus "-<endpointVersion>"; compare on the configuration route
        return camel.getRoutes().stream().map(org.apache.camel.Route::getRouteId)
                .map(id -> id.substring(0, id.lastIndexOf('-'))).toList();
    }

    private static String camelRouteId(RouteRow route) {
        return RouteInstanceFactory.ROUTE_ID_PREFIX + route.id();
    }

    private void send(UUID endpoint, String body) {
        try (ProducerTemplate producer = camel.createProducerTemplate()) {
            producer.sendBody(factory.instanceOf(endpoint).orElseThrow().consumerUri(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The config module's publish path as the application composes it (ConfigPersistenceConfiguration). */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class ConfigPublishPath {

        @Bean
        PlatformTransactionManager transactionManager() {
            return stamped;
        }

        @Bean
        TenantContext tenantContext() {
            return RoutingTestDatabase.tenant(TENANT);
        }

        @Bean
        JdbcFlowRepository jdbcFlowRepository(ObjectProvider<TenantContext> tenantContexts) {
            return new JdbcFlowRepository(jdbc, tenantContexts);
        }

        @Bean
        FlowAdminService flowAdminService(JdbcFlowRepository flows, ApplicationEventPublisher events,
                TenantContext tenantContext) {
            return new FlowAdminService(flows, mock(SystemAdminService.class), mock(ContractAdminService.class),
                    mock(MappingPublishPinCheck.class), events, tenantContext);
        }
    }

    /** Configuration rows as the config module's draft editing leaves them, written in tenant-stamped transactions. */
    private static final class Seed {

        private final UUID author = UUID.randomUUID();
        private final UUID system = UUID.randomUUID();
        private final UUID contractVersion = UUID.randomUUID();
        private final UUID interfaceId = UUID.randomUUID();
        private final TransactionTemplate tx = new TransactionTemplate(stamped);

        Seed() {
            write("insert into public.system (id, tenant_id, code, name, direction, type, settings)"
                    + " values (:id::uuid, :tenant::uuid, :code, 'T-116 system', 'BOTH', 'API', '{}'::jsonb)",
                    "id", system, "code", code("sys"));
            UUID contract = UUID.randomUUID();
            write("insert into public.contract (id, tenant_id, system_id, code, role, name)"
                    + " values (:id::uuid, :tenant::uuid, :system::uuid, :code, 'SOURCE_REQUEST', 'T-116 contract')",
                    "id", contract, "system", system, "code", code("ct"));
            write("insert into public.contract_version (id, tenant_id, contract_id, version_label,"
                    + " json_schema_draft_2020_12, author_id)"
                    + " values (:id::uuid, :tenant::uuid, :contract::uuid, '1.0', '{}'::jsonb, :author::uuid)",
                    "id", contractVersion, "contract", contract, "author", author);
            write("insert into public.interface (id, tenant_id, code, name)"
                    + " values (:id::uuid, :tenant::uuid, :code, 'T-116 interface')",
                    "id", interfaceId, "code", code("if"));
        }

        UUID targetEndpoint(String baseUrl) {
            UUID id = UUID.randomUUID();
            write("insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url, http_method,"
                    + " auth_method, timeout_ms)"
                    + " values (:id::uuid, :tenant::uuid, :system::uuid, :code, 'T-116 target', :url, 'POST',"
                    + " 'NO_AUTH', 5000)",
                    "id", id, "system", system, "code", code("te"), "url", baseUrl);
            return id;
        }

        RouteRow draftRoute(UUID endpoint, int sequence, String status) {
            RouteRow route = new RouteRow(UUID.randomUUID().toString(), sequence, null, false, status,
                    endpoint.toString());
            write("insert into public.route (id, tenant_id, interface_id, target_endpoint_id, sequence, status)"
                    + " values (:id::uuid, :tenant::uuid, :interface::uuid, :endpoint::uuid, :sequence, :status)",
                    "id", route.id(), "interface", interfaceId, "endpoint", endpoint, "sequence", sequence,
                    "status", status);
            return route;
        }

        /** A route's mapping bound to the contract version, with the mapping version its content pins. */
        MappingRow boundMapping(RouteRow route) {
            UUID mapping = UUID.randomUUID();
            UUID mappingVersion = UUID.randomUUID();
            write("insert into public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression,"
                    + " source_contract_version_id, target_contract_version_id)"
                    + " values (:id::uuid, :tenant::uuid, :route::uuid, '[]'::jsonb, '$', :cv::uuid, :cv::uuid)",
                    "id", mapping, "route", route.id(), "cv", contractVersion);
            write("insert into public.mapping_version (id, tenant_id, mapping_id, version, source_contract_version_id,"
                    + " target_contract_version_id, engine, field_mappings, compiled_expression, content_sha256,"
                    + " author_id)"
                    + " values (:id::uuid, :tenant::uuid, :mapping::uuid, 1, :cv::uuid, :cv::uuid, 'JSONATA',"
                    + " '[]'::jsonb, '$', sha256(:mapping::text::bytea), :author::uuid)",
                    "id", mappingVersion, "mapping", mapping, "cv", contractVersion, "author", author);
            return new MappingRow(mapping.toString(), route.id(), "[]", "$", contractVersion.toString(),
                    contractVersion.toString(), mappingVersion.toString());
        }

        /** An APPROVED interface version whose snapshot holds these routes and mappings (the publish input). */
        void approvedVersion(int version, List<RouteRow> routes, List<MappingRow> mappings) {
            String snapshot = FlowBundle.serialize("T116", "T-116 interface", "PER_KEY", "$.id", routes, mappings);
            write("update public.interface set current_version = :version where id = :id::uuid",
                    "id", interfaceId, "version", version);
            write("insert into public.interface_version (tenant_id, interface_id, version, status, business_key_rule,"
                    + " snapshot, source_contract_version_id, author_id, approver_id)"
                    + " values (:tenant::uuid, :interface::uuid, :version, 'APPROVED', '$.id', :snapshot::jsonb,"
                    + " :cv::uuid, :author::uuid, :approver::uuid)",
                    "interface", interfaceId, "version", version, "snapshot", snapshot, "cv", contractVersion,
                    "author", author, "approver", UUID.randomUUID());
        }

        /** The interface's status and published-version pointer, as one comparable pair. */
        List<String> publishPointer() {
            return tx.execute(status -> jdbc.sql("select status, published_version::text as pv from public.interface"
                    + " where id = :id::uuid").param("id", interfaceId.toString())
                    .query((rs, n) -> java.util.Arrays.asList(rs.getString("status"), rs.getString("pv"))).single());
        }

        String interfaceStatus() {
            return tx.execute(status -> jdbc.sql("select status from public.interface where id = :id::uuid")
                    .param("id", interfaceId.toString()).query(String.class).single());
        }

        private void write(String sql, Object... namesAndValues) {
            tx.executeWithoutResult(status -> {
                JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", TENANT.toString());
                for (int i = 0; i < namesAndValues.length; i += 2) {
                    Object value = namesAndValues[i + 1];
                    statement = statement.param((String) namesAndValues[i],
                            value instanceof UUID ? value.toString() : value);
                }
                statement.update();
            });
        }

        private static String code(String prefix) {
            return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /** The production stamp of TenantStampingTransactionManager: app.tenant_id set per transaction (R11). */
    private static PlatformTransactionManager stampingManager(DataSource dataSource, TenantContext tenantContext) {
        return new JdbcTransactionManager(dataSource) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws java.sql.SQLException {
                super.prepareTransactionalConnection(connection, definition);
                try (var stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                    stamp.setString(1, tenantContext.requireTenantId().toString());
                    stamp.execute();
                }
            }
        };
    }
}
