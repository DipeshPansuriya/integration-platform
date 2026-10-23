package io.integrationplatform.config.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository.ApprovedTableRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.config.system.application.ApprovedTableService;
import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T189 (B30): when routing's DB slice is absent the composition root's {@code dbAccountWidthCheck} bean method returns
 * null (a Spring NullBean). The REAL container must then hand {@link SystemPersistenceConfiguration} no check, so its
 * default refuses every approval (fail closed) - proven through a real context, not a mock of ObjectProvider.
 */
class SystemPersistenceWidthCheckWiringTest {

    private static final String SYSTEM = "88888888-8888-8888-8888-888888888888";
    private static final String ROW = "55555555-5555-5555-5555-555555555555";

    /** The shape of platform-app CompositionConfiguration.dbAccountWidthCheck with no DbAccountWidth bean. */
    @Configuration(proxyBeanMethods = false)
    static class AbsentRoutingSlice {
        @Bean
        DbAccountWidthCheck dbAccountWidthCheck() {
            return null;
        }
    }

    @Test
    @DisplayName("no width check composed (NullBean): the default refuses an approval as DB_ACCOUNT_UNVERIFIED and decides nothing")
    void anAbsentWidthCheckRefusesEveryApproval() {
        JdbcApprovedTableRepository tables = mock(JdbcApprovedTableRepository.class);
        JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        when(systems.findSystem(SYSTEM)).thenReturn(Optional.of(
                new SystemDetail(SYSTEM, "orders-db", "Orders DB", "TARGET", "DB", "{}")));
        when(tables.find(ROW)).thenReturn(Optional.of(new ApprovedTableRow(ROW, SYSTEM, "orders_in",
                "PENDING_APPROVAL", requester.toString(), Instant.now(), null, null, true)));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AbsentRoutingSlice.class)) {
            ObjectProvider<DbAccountWidthCheck> provider = context.getBeanProvider(DbAccountWidthCheck.class);
            assertThat(provider.getIfAvailable()).as("a NullBean resolves to no check").isNull();

            @SuppressWarnings("unchecked")
            ObjectProvider<io.integrationplatform.tenancy.TenantContext> tenants = mock(ObjectProvider.class);
            ApprovedTableService service = new SystemPersistenceConfiguration().approvedTableService(tables, systems,
                    event -> { }, tenants, provider);

            assertThatThrownBy(() -> service.decide(SYSTEM, ROW, "APPROVE", approver))
                    .isInstanceOfSatisfying(ConfigApiException.class,
                            e -> {
                        assertThat(e.httpStatus()).isEqualTo(409);
                        assertThat(String.valueOf(e.body().get("error"))).startsWith("DB_ACCOUNT_UNVERIFIED");
                    });
            verify(tables, never()).decide(anyString(), anyString(), anyString());
        }
    }
}
