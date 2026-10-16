package io.integrationplatform.ingest.adapter.in;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * RED skeleton (row 278 T107, T-063): the request filter the tests compile against - passes through, opens no
 * journey yet; not registered. The GREEN commit fills it in.
 */
public class CorrelationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
    }
}
