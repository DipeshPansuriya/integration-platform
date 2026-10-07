package io.integrationplatform.tenancy.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * E4-F1-S5 (T-186): a bearer token the platform refuses - for example one without the platform API audience, from a
 * client created without the realm's default audience scope (ADR-007 Decisions 3 and 4) - is answered 401 by the
 * resource server AND audited: one structured WARN event {@code event=token.refused} with the OAuth error code, the
 * method and the path. Never the token, never a claim value from it (R14). Spring Security publishes the failure of
 * the bearer authentication as an application event; this listener turns it into the audit line, so the resource
 * server chain stays as it is. (A request that also named a tenant is additionally recorded by the tenant-denial
 * recorder as NO_IDENTITY, E1-F1-S1.)
 */
@Component
class RefusedTokenAuditor {

    private static final Logger LOG = LoggerFactory.getLogger(RefusedTokenAuditor.class);
    private static final int MAX_PATH = 512;

    @EventListener
    void onAuthenticationFailure(AbstractAuthenticationFailureEvent failure) {
        if (!(failure.getAuthentication() instanceof BearerTokenAuthenticationToken)) {
            return;
        }
        String error = failure.getException() instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : "invalid_token";
        String method = "-";
        String path = "-";
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            method = clean(request.getMethod());
            path = clean(request.getRequestURI());
        }
        LOG.atWarn()
                .addKeyValue("event", "token.refused")
                .addKeyValue("error", error)
                .addKeyValue("method", method)
                .addKeyValue("path", path)
                .log("event=token.refused error={} method={} path={}", error, method, path);
    }

    /** No line breaks or control characters from the request reach the log (log forging), and the length is capped. */
    private static String clean(String value) {
        String flat = value.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > MAX_PATH ? flat.substring(0, MAX_PATH) : flat;
    }
}
