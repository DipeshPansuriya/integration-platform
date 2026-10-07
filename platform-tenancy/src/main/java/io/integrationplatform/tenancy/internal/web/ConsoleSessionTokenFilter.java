package io.integrationplatform.tenancy.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E4 (T-097), ADR-007 Decision 1: turns a console session into the same identity a bearer token gives. Per request the
 * session's authorized client yields the access token (refreshed through the token endpoint when it is about to
 * expire); the token is decoded by the resource server's own {@link JwtDecoder} - every Decision-4 check, the audience
 * included - and becomes a {@link JwtAuthenticationToken} for this request only (never saved to the session). The
 * principal resolver and the tenant-forgery filter then run unchanged.
 *
 * <p>No usable token (no authorized client, a refresh refused or the provider unreachable, a token that fails
 * validation) leaves the request unauthenticated: the chain answers 401, never data. The token never leaves the server.
 */
final class ConsoleSessionTokenFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSessionTokenFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClients;
    private final JwtDecoder decoder;

    ConsoleSessionTokenFilter(OAuth2AuthorizedClientManager authorizedClients, JwtDecoder decoder) {
        this.authorizedClients = authorizedClients;
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        Authentication current = holder.getContext().getAuthentication();
        if (current instanceof OAuth2AuthenticationToken login && login.isAuthenticated()) {
            SecurityContext perRequest = holder.createEmptyContext();
            accessToken(login, request, response).ifPresent(perRequest::setAuthentication);
            holder.setContext(perRequest);
        }
        chain.doFilter(request, response);
    }

    private Optional<JwtAuthenticationToken> accessToken(OAuth2AuthenticationToken login, HttpServletRequest request,
            HttpServletResponse response) {
        try {
            OAuth2AuthorizedClient client = authorizedClients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(login.getAuthorizedClientRegistrationId())
                    .principal(login)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
            if (client == null) {
                return Optional.empty();
            }
            Jwt jwt = decoder.decode(client.getAccessToken().getTokenValue());
            return Optional.of(new JwtAuthenticationToken(jwt, List.of()));
        } catch (RuntimeException unusable) {
            // The class only: a token, a secret or a provider answer never reaches the log (R14).
            LOG.atInfo().addKeyValue("event", "console.session.unauthenticated")
                    .log("event=console.session.unauthenticated reason={}", unusable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
