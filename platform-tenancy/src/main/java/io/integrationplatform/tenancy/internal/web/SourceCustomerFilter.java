package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E4-F1-S2 body cross-check (T-098, ADR-007 Decision 2): on a source system's call, every {@code customerId} /
 * {@code customer_id} of the JSON body (top level and per bulk record) must name the calling credential's customer.
 * A mismatch - another customer, or a structured value - is 403 before any controller runs (nothing is persisted)
 * and recorded as BODY_CUSTOMER with the bound tenant, never the forged value. No customerId at all is fine: the
 * customer comes from the credential ({@link RequestTenantContext#currentSource()}). Runs after
 * {@link TenantForgeryFilter}; console users' requests pass untouched.
 *
 * <p>The whole body is inspected, so a forged customer cannot hide behind the first records of a bulk call. It is
 * bounded by the bulk cap of the common API (PRD: 10 MB per bulk call); a larger source body is 413 here, as intake
 * would answer it, and nothing more of it is read.
 */
final class SourceCustomerFilter extends OncePerRequestFilter {

    /** The common API's bulk cap (PRD, ADR-011): 10 MB per call. */
    static final int SOURCE_BODY_LIMIT = 10 * 1024 * 1024;

    private final RequestTenantContext tenantContext;
    private final TenantDenialRecorder recorder;

    SourceCustomerFilter(RequestTenantContext tenantContext, TenantDenialRecorder recorder) {
        this.tenantContext = tenantContext;
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ClientBinding> source = tenantContext.currentSource();
        if (source.isEmpty() || !TenantFieldScanner.isJson(request)) {
            chain.doFilter(request, response);
            return;
        }
        ClientBinding binding = source.get();
        BoundedBodyRequest whole = new BoundedBodyRequest(request, SOURCE_BODY_LIMIT + 1);
        if (whole.prefix().length > SOURCE_BODY_LIMIT) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        for (TenantFieldScanner.CustomerField field : TenantFieldScanner.customerFields(whole.prefix())) {
            if (field.value() == null || !field.value().strip().equals(binding.customerId())) {
                recorder.record(new DeniedTenantAttempt(binding.tenantId(), DeniedTenantAttempt.Channel.BODY_CUSTOMER,
                        request.getMethod(), request.getRequestURI(), Instant.now()));
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(whole, response);
    }
}
