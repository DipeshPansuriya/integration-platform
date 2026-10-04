package io.integrationplatform.tenancy.internal.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenancyTestImages;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T-004 against a real PostgreSQL (E1-F2-S1, traces E1-C3, E1-C5; R08, R09, R11). Container group (R03): runs only with
 * ./mvnw -Pcontainer, one Docker suite at a time (R06). Credentials come from Testcontainers via @ServiceConnection (R07).
 * The lane-A fixture table holds two rows of tenant A and one of tenant B; each test runs in a rolled-back transaction.
 *
 * <pre>
 * &#64;tenant
 * Scenario: A bound tenant reads only its own rows
 *   Given rows of tenant A and tenant B in a tenant-scoped table
 *   And tenant A is bound in the TenantContext
 *   When an adapter reads the table through the tenant-scoped repository base
 *   Then only tenant A's rows are returned
 *   And an update aimed at a tenant-B row changes nothing
 *
 * &#64;negative &#64;tenant
 * Scenario: No bound tenant is refused before any statement
 *   Given no tenant is bound in the TenantContext
 *   When an adapter reads through the tenant-scoped repository base
 *   Then NoTenantBoundException is thrown
 *   And no statement reaches the database
 *
 * &#64;negative
 * Scenario: Access outside a transaction is refused before any statement
 *   Given tenant A is bound and no transaction is active
 *   When an adapter reads through the tenant-scoped repository base
 *   Then the access is refused and no statement reaches the database
 *
 * Scenario: The ::uuid cast binds a string tenant id
 *   Given the bound tenant id is sent to PostgreSQL as a string parameter
 *   When the repository base runs "tenant_id = :tenant_id::uuid"
 *   Then the rows of that tenant are found
 *   And the same predicate without the cast is refused by PostgreSQL
 * </pre>
 */
@Tag("container")
@Testcontainers
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/fixture/lane-a")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StatementCountingDataSource.class)
class TenantScopedRepositoryContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TenancyTestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID TENANT_B_ROW = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    @Autowired
    private JdbcClient jdbcClient;

    private final AtomicReference<UUID> boundTenant = new AtomicReference<>();
    private LaneAFixtureNoteRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LaneAFixtureNoteRepository(jdbcClient, () -> Optional.ofNullable(boundTenant.get()));
        StatementCountingDataSource.reset();
    }

    @Test
    void aBoundTenantReadsOnlyItsOwnRows() {
        boundTenant.set(TENANT_A);
        assertThat(repository.titles()).containsExactlyInAnyOrder("a-first", "a-second");

        boundTenant.set(TENANT_B);
        assertThat(repository.titles()).containsExactly("b-only");
    }

    @Test
    void anUpdateAimedAtAnotherTenantsRowChangesNothing() {
        boundTenant.set(TENANT_A);

        assertThat(repository.retitle(TENANT_B_ROW, "taken over")).isZero();

        assertThat(jdbcClient.sql("select title from lane_a_fixture_note where id = :id")
                .param("id", TENANT_B_ROW).query(String.class).single()).isEqualTo("b-only");
    }

    @Test
    void noBoundTenantIsRefusedBeforeAnyStatement() {
        assertThatThrownBy(repository::titles).isInstanceOf(NoTenantBoundException.class);
        assertThatThrownBy(() -> repository.retitle(TENANT_B_ROW, "x")).isInstanceOf(NoTenantBoundException.class);

        assertThat(StatementCountingDataSource.statements()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accessOutsideATransactionIsRefusedBeforeAnyStatement() {
        boundTenant.set(TENANT_A);

        assertThatThrownBy(repository::titles)
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NoTenantBoundException.class);

        assertThat(StatementCountingDataSource.statements()).isZero();
    }

    @Test
    void theUuidCastBindsAStringTenantId() {
        boundTenant.set(TENANT_A);
        assertThat(repository.titlesStartingWith("a-s")).containsExactly("a-second");
        assertThat(StatementCountingDataSource.statements()).isPositive();

        // Control (R09): the same predicate without the explicit cast is refused - the cast is load-bearing.
        assertThatThrownBy(() -> jdbcClient.sql("select title from lane_a_fixture_note where tenant_id = :tenant_id")
                .param("tenant_id", TENANT_A.toString()).query(String.class).list())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("operator does not exist: uuid = character varying");
    }
}
