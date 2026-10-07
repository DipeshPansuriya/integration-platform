package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt.Channel;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E1-F1-S1: a header, query parameter or JSON body field naming a tenant other than the bound one is ignored (nothing
 * reads it - the tenant comes only from {@link RequestTenantContext}) and recorded once per channel. Runs after the
 * resolver; a request without a bound tenant is left to the entry point (NO_IDENTITY). The bound tenant is the console
 * user's or, for a source system's call (E4 T-098), the calling credential's.
 */
final class TenantForgeryFilter extends OncePerRequestFilter {

    private final RequestTenantContext tenantContext;
    private final TenantDenialRecorder recorder;

    TenantForgeryFilter(RequestTenantContext tenantContext, TenantDenialRecorder recorder) {
        this.tenantContext = tenantContext;
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<LoginBinding> human = tenantContext.currentBinding();
        Optional<ClientBinding> source = human.isPresent() ? Optional.empty() : tenantContext.currentSource();
        if (human.isEmpty() && source.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        UUID tenantId = human.map(LoginBinding::tenantId).orElseGet(() -> source.get().tenantId());
        String tenantCode = human.map(LoginBinding::tenantCode).orElseGet(() -> source.get().tenantCode());
        HttpServletRequest forward = request;
        byte[] body = new byte[0];
        if (TenantFieldScanner.isJson(request)) {
            BoundedBodyRequest replayable = new BoundedBodyRequest(request);
            body = replayable.prefix();
            forward = replayable;
        }
        EnumSet<Channel> forged = EnumSet.noneOf(Channel.class);
        for (TenantFieldScanner.TenantField field : TenantFieldScanner.scan(request, body)) {
            if (!TenantBoundAuthentication.namesTenant(tenantId, tenantCode, field.value())) {
                forged.add(field.channel());
            }
        }
        for (Channel channel : forged) {
            recorder.record(new DeniedTenantAttempt(
                    tenantId, channel, request.getMethod(), request.getRequestURI(), Instant.now()));
        }
        chain.doFilter(forward, response);
    }
}
