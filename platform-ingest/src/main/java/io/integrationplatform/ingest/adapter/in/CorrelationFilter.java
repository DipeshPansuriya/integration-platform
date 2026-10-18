package io.integrationplatform.ingest.adapter.in;

import io.integrationplatform.ingest.port.CorrelationContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.Ordered;

/**
 * T-063 (row 278 T107, E12-F1-S1): the correlation id is set at intake. Every HTTP request runs inside one journey
 * opened from its {@code traceparent} header (a fresh trace when it has none or it is not a valid W3C value), so the
 * intake transaction's log lines and its outbox row carry the id; the scope closes with the request. Runs first
 * (highest precedence), before the security chain, so authentication lines carry the id too.
 */
public class CorrelationFilter implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String inbound = request instanceof HttpServletRequest http ? http.getHeader("traceparent") : null;
        try (CorrelationContext.Scope journey = CorrelationContext.open(inbound)) {
            chain.doFilter(request, response);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
