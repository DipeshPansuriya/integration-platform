package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * T-049 (R08/R11): the application's composite TenantContext answers the one source bound on the thread, nothing
 * when none is, and refuses to choose between two different bound tenants.
 */
class WorkerAwareTenantContextTest {

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final WorkerTenantBinding worker = new WorkerTenantBinding();
    private final MutableContext request = new MutableContext();
    private final WorkerAwareTenantContext composite = composite();

    private WorkerAwareTenantContext composite() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("request", request);
        beans.addBean("worker", worker);
        WorkerAwareTenantContext built = new WorkerAwareTenantContext(beans.getBeanProvider(TenantContext.class));
        beans.addBean("composite", built); // it is a TenantContext bean itself - it must never ask itself
        built.afterSingletonsInstantiated();
        return built;
    }

    @Test
    @DisplayName("nothing bound -> empty (every tenant-scoped call then fails closed)")
    void nothingBound() {
        assertThat(composite.currentTenantId()).isEmpty();
    }

    @Test
    @DisplayName("a request thread answers the request's tenant")
    void requestTenant() {
        request.tenant = TENANT_A;
        assertThat(composite.currentTenantId()).contains(TENANT_A);
    }

    @Test
    @DisplayName("a worker thread answers the worker's binding, for the work only")
    void workerTenant() {
        UUID seen = worker.callAs(TENANT_B, () -> composite.currentTenantId().orElseThrow());
        assertThat(seen).isEqualTo(TENANT_B);
        assertThat(composite.currentTenantId()).isEmpty();
    }

    @Test
    @DisplayName("two sources bound to different tenants on one thread -> refused, never a choice")
    void twoDifferentTenantsRefused() {
        request.tenant = TENANT_A;
        worker.runAs(TENANT_B, () -> assertThatThrownBy(composite::currentTenantId)
                .isInstanceOf(IllegalStateException.class));
    }

    @Test
    @DisplayName("two sources agreeing on one tenant answer it")
    void twoAgreeingSources() {
        request.tenant = TENANT_A;
        worker.runAs(TENANT_A, () -> assertThat(composite.currentTenantId()).contains(TENANT_A));
    }

    private static final class MutableContext implements TenantContext {

        private UUID tenant;

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(tenant);
        }
    }
}
