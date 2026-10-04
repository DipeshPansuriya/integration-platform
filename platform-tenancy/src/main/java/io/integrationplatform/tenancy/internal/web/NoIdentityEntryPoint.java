package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * E1-F1-S1 "No identity, no tenant": every 401 (no token, or a token that failed validation) is answered by the bearer
 * entry point; when the request named a tenant anywhere it is recorded as NO_IDENTITY with no bound tenant. The login
 * bootstrap is never called on this path.
 */
final class NoIdentityEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint delegate;
    private final TenantDenialRecorder recorder;

    NoIdentityEntryPoint(AuthenticationEntryPoint delegate, TenantDenialRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        if (!TenantFieldScanner.scan(request, TenantFieldScanner.jsonBodyPrefix(request)).isEmpty()) {
            recorder.record(new DeniedTenantAttempt(null, DeniedTenantAttempt.Channel.NO_IDENTITY,
                    request.getMethod(), request.getRequestURI(), Instant.now()));
        }
        delegate.commence(request, response, failure);
    }
}
