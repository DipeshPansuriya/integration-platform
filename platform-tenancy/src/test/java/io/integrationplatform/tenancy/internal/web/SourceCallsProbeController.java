package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.ClientBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only intake endpoint for E4-F1-S2 (T-098), the source-call twin of {@link TenantRowsProbeController}: it takes
 * the customer only from the calling credential's binding (never from the body), records every call that reached it
 * ("nothing is persisted" is asserted on {@link #PERSISTED}) and reads {@code source_credential} in a tenant-bound
 * transaction, so the answer shows which tenant the stamp - and so row-level security - used.
 */
@RestController
@RequestMapping("/api/test/source-messages")
class SourceCallsProbeController {

    /** One accepted call: the customer and tenant it was stored under. */
    record Persisted(String customerId, UUID tenantId) {
    }

    static final List<Persisted> PERSISTED = new CopyOnWriteArrayList<>();

    private final ObjectProvider<RequestTenantContext> tenantContext;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SourceCallsProbeController(ObjectProvider<RequestTenantContext> tenantContext, ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<PlatformTransactionManager> transactions) {
        this.tenantContext = tenantContext;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @PostMapping
    Map<String, Object> accept(@RequestBody(required = false) Map<String, Object> body) {
        RequestTenantContext context = tenantContext.getObject();
        UUID tenant = context.requireTenantId();
        String customer = context.currentSource().map(ClientBinding::customerId).orElse(null);
        List<UUID> visibleTenants = new TransactionTemplate(transactions.getObject()).execute(status -> jdbc.getObject()
                .queryForList("select distinct tenant_id from public.source_credential", UUID.class));
        PERSISTED.add(new Persisted(customer, tenant));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("customerId", customer);
        answer.put("tenantId", tenant);
        answer.put("humanBound", context.currentBinding().isPresent());
        answer.put("visibleTenants", visibleTenants);
        return answer;
    }
}
