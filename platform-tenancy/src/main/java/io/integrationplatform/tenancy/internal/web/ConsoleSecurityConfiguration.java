package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.principal.PrincipalTenantResolver;
import io.integrationplatform.tenancy.internal.principal.RequestTenantContext;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E4-F1-S1 (T-097), ADR-007 Decision 1: the console's login as a backend for frontend. Ordered before the bearer chain
 * ({@code tenancySecurityFilterChain}, unchanged) with its own matcher:
 *
 * <ul>
 *   <li>the login and logout endpoints - {@code /oauth2/**} (start), {@code /login/**} (code callback),
 *       {@code /logout};</li>
 *   <li>{@code /api/**} and {@code /v3/api-docs/**} calls that carry NO {@code Authorization} header but the console's
 *       session cookie. A bearer call always takes the bearer chain; a call with neither gets its 401 there.</li>
 * </ul>
 *
 * Login: Authorization Code with PKCE (S256), the platform as the confidential client (registration
 * {@value ConsoleSettings#REGISTRATION_ID}, issuer {@code OIDC_ISSUER_URI}, client from tenancy-console.yml). The
 * session cookie is HttpOnly + Secure + SameSite=Lax; sessions live in PostgreSQL (Spring Session JDBC, assembled by
 * platform-app). The browser never receives a token: per request {@link ConsoleSessionTokenFilter} takes the session's
 * access token (refreshing it), decodes it with the resource server's decoder (every Decision-4 check) and the same
 * {@link PrincipalTenantResolver} and {@link TenantForgeryFilter} bind the tenant - so every API endpoint behaves for a
 * console session exactly as for a bearer token.
 *
 * <p>CSRF is on for state-changing session requests (cookie {@code XSRF-TOKEN}, header {@code X-XSRF-TOKEN}). No
 * session: the API answers 401 JSON, never a redirect; the login entry redirects to the identity provider. Every call
 * to the identity provider (discovery, code exchange, refresh, ID-token keys) goes through {@link IdentityProviderClient}
 * (R13); while it is unreachable a login fails with a clear 503 JSON error.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = ConsoleSettings.PREFIX, name = "client-id")
class ConsoleSecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSecurityConfiguration.class);

    static final String LOGIN_ENTRY =
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/"
                    + ConsoleSettings.REGISTRATION_ID;

    @Bean
    ConsoleSettings consoleSettings(Environment environment) {
        return ConsoleSettings.from(environment);
    }

    @Bean
    ConsoleClientRegistrations consoleClientRegistrations(Environment environment, ConsoleSettings settings,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        // The same registry as the resource server's decoder: one identity-provider breaker for the application.
        RestTemplate identityProvider = IdentityProviderClient.restTemplate(IdentityProviderSettings.from(environment),
                meters.getIfAvailable(CompositeMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        return new ConsoleClientRegistrations(
                environment.getRequiredProperty(TenancySecurityConfiguration.ISSUER_URI).strip(), settings,
                identityProvider);
    }

    /** ID tokens of the console login: keys fetched through the R13 client, RS256/ES256 only, 60 s skew. */
    @Bean
    JwtDecoderFactory<ClientRegistration> consoleIdTokenDecoderFactory(ConsoleClientRegistrations registrations) {
        Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
        return registration -> decoders.computeIfAbsent(registration.getRegistrationId(), id -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(registrations.identityProvider())
                    .jwsAlgorithms(allowed -> {
                        allowed.clear();
                        allowed.addAll(TenancySecurityConfiguration.ALLOWED_ALGORITHMS);
                    })
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(TenancySecurityConfiguration.MAX_CLOCK_SKEW),
                    new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        });
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10) // before tenancySecurityFilterChain
    SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http, ConsoleSettings settings,
            ConsoleClientRegistrations registrations, JwtDecoder decoder, LoginBootstrap loginBootstrap,
            TenantDenialRecorder tenantDenialRecorder, RequestTenantContext requestTenantContext) throws Exception {
        HttpSessionOAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
        RestClient tokenEndpoint = tokenEndpointClient(registrations.identityProvider());
        RestClientAuthorizationCodeTokenResponseClient codeExchange = new RestClientAuthorizationCodeTokenResponseClient();
        codeExchange.setRestClient(tokenEndpoint);
        RestClientRefreshTokenTokenResponseClient refresh = new RestClientRefreshTokenTokenResponseClient();
        refresh.setRestClient(tokenEndpoint);
        DefaultOAuth2AuthorizedClientManager sessionTokens =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        sessionTokens.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(token -> token.accessTokenResponseClient(refresh))
                .build());

        AuthenticationEntryPoint apiEntryPoint = new NoIdentityEntryPoint(
                (request, response, failure) -> json(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"error\":\"unauthenticated\",\"login\":\"" + LOGIN_ENTRY + "\"}"),
                tenantDenialRecorder);
        AuthenticationFailureHandler loginFailure = ConsoleSecurityConfiguration::loginFailed;

        http.securityMatcher(consoleRequests(settings.sessionCookieName()))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .securityContext(context -> context.securityContextRepository(new EagerSessionContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> {
                            if (isApi(request)) {
                                apiEntryPoint.commence(request, response, failure);
                            } else {
                                response.sendRedirect(request.getContextPath() + LOGIN_ENTRY);
                            }
                        })
                        .accessDeniedHandler((request, response, denied) -> {
                            if (signedIn()) {
                                json(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"forbidden\"}");
                            } else { // a dead session's write is "no session" (401), not a CSRF story
                                apiEntryPoint.commence(request, response, null);
                            }
                        }))
                .oauth2Login(login -> login
                        .clientRegistrationRepository(registrations)
                        .authorizedClientRepository(authorizedClients)
                        .loginPage(LOGIN_ENTRY)
                        .tokenEndpoint(token -> token.accessTokenResponseClient(codeExchange))
                        .defaultSuccessUrl(settings.postLoginUri(), true)
                        .failureHandler(loginFailure)
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                            @Override
                            public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(loginFailure);
                                return filter;
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(new JsonOidcLogout(registrations, settings.postLogoutRedirectUri())))
                .addFilterAfter(new SessionStoreUnavailableFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new ConsoleSessionTokenFilter(sessionTokens, decoder), ExceptionTranslationFilter.class)
                .addFilterAfter(new PrincipalTenantResolver(loginBootstrap, tenantDenialRecorder),
                        ConsoleSessionTokenFilter.class)
                .addFilterAfter(new TenantForgeryFilter(requestTenantContext, tenantDenialRecorder),
                        PrincipalTenantResolver.class);
        return http.build();
    }

    /** Login and logout endpoints, and token-less API calls that carry the console's session cookie. */
    static RequestMatcher consoleRequests(String sessionCookieName) {
        RequestMatcher noAuthorizationHeader = request -> request.getHeader("Authorization") == null;
        RequestMatcher sessionCookie = request -> {
            if (request.getCookies() == null) {
                return false;
            }
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (sessionCookieName.equals(cookie.getName())) {
                    return true;
                }
            }
            return false;
        };
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/oauth2/**"),
                PathPatternRequestMatcher.pathPattern("/login/**"),
                PathPatternRequestMatcher.pathPattern("/logout"),
                new AndRequestMatcher(ConsoleSecurityConfiguration::isApi, noAuthorizationHeader, sessionCookie));
    }

    private static boolean isApi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/") || path.startsWith("/v3/api-docs");
    }

    private static boolean signedIn() {
        Authentication current = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        return current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken);
    }

    /** The token endpoint client: the R13 interceptor and timeout of {@code identityProvider}, OAuth2 converters. */
    private static RestClient tokenEndpointClient(RestTemplate identityProvider) {
        return RestClient.builder(identityProvider)
                .messageConverters(converters -> {
                    converters.addFirst(new OAuth2AccessTokenResponseHttpMessageConverter());
                    converters.addFirst(new FormHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /**
     * R13 fallback for a login: an identity provider that cannot be reached is a clear 503 (try again shortly), any
     * other failure a 401 - never a 500, never a half-created session. No detail of the provider's answer is echoed.
     * Row 571 follow-up: a browser navigation (Accept names text/html, not an API path) is redirected to the console's
     * sign-in state with a short flag instead - its loop guard shows "Try again" - so a cancelled or expired login
     * never ends on raw JSON with the single-use code in the address bar. API clients keep the JSON answer.
     */
    static void loginFailed(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        boolean down = unreachable(failure);
        if (browserNavigation(request)) {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(request.getContextPath() + "/#/login?error="
                    + (down ? "idp_unavailable" : "login_failed"));
        } else if (down) {
            json(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "{\"error\":\"identity_provider_unavailable\","
                    + "\"message\":\"The login service cannot be reached. Please try again in a few minutes.\"}");
        } else {
            json(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"login_failed\","
                    + "\"message\":\"The login did not complete. Please start it again.\",\"login\":\""
                    + LOGIN_ENTRY + "\"}");
        }
    }

    private static boolean browserNavigation(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html") && !isApi(request);
    }

    private static boolean unreachable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConsoleClientRegistrations.IdentityProviderUnavailableException
                    || cause instanceof IOException
                    || cause instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
        }
        return false;
    }

    static void json(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /**
     * The session's security context, loaded BEFORE it is put in the holder (Spring Security defers it by default).
     * Why: the session lives in PostgreSQL, so loading it opens a transaction; the tenant-stamping transaction manager
     * then asks the request's tenant context, which reads the security context holder - a still-deferred context would
     * load the session again, recursively, one pooled connection per level until the pool is empty (measured
     * 2026-10-07: 10 of 10 connections, the login callback waited 30 s). Loaded eagerly, the holder is empty while the
     * session is read, and every later read finds the session already in the request.
     */
    static final class EagerSessionContextRepository implements SecurityContextRepository {

        private final SecurityContextRepository delegate;

        EagerSessionContextRepository() {
            this(new DelegatingSecurityContextRepository(
                    new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository()));
        }

        EagerSessionContextRepository(SecurityContextRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
            DeferredSecurityContext deferred = delegate.loadDeferredContext(request);
            SecurityContext loaded;
            boolean generated;
            try {
                loaded = deferred.get();
                generated = deferred.isGenerated();
            } catch (DataAccessException | TransactionException storeDown) {
                // T197 R2: the session store (PostgreSQL) is unreachable. Not "no session" (a 401 or login redirect for
                // a user who may be signed in) and not an escaped exception (a 500 whose ERROR dispatch repeats the
                // lookup). The request is marked, the next filter answers 503. The class only is logged: the message
                // can carry the JDBC URL. The filters' dispatcher types stay as they are - dropping ERROR could
                // expose the container's in-memory session.
                LOG.warn("the session store is unavailable ({})", storeDown.getClass().getName());
                request.setAttribute(SESSION_STORE_DOWN, Boolean.TRUE);
                loaded = SecurityContextHolder.createEmptyContext();
                generated = true;
            }
            SecurityContext loadedContext = loaded;
            boolean generatedContext = generated;
            return new DeferredSecurityContext() {
                @Override
                public SecurityContext get() {
                    return loadedContext;
                }

                @Override
                public boolean isGenerated() {
                    return generatedContext;
                }
            };
        }

        @Override
        @SuppressWarnings("deprecation")
        public SecurityContext loadContext(
                org.springframework.security.web.context.HttpRequestResponseHolder requestResponseHolder) {
            return delegate.loadContext(requestResponseHolder);
        }

        @Override
        public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            delegate.saveContext(context, request, response);
        }

        @Override
        public boolean containsContext(HttpServletRequest request) {
            return delegate.containsContext(request);
        }
    }

    /** Marks a request whose session could not be read because the session store is down (T197 R2). */
    static final String SESSION_STORE_DOWN = ConsoleSecurityConfiguration.class.getName() + ".SESSION_STORE_DOWN";

    /** Answers 503 for a request marked by {@link EagerSessionContextRepository}; the chain does not continue. */
    static final class SessionStoreUnavailableFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(SESSION_STORE_DOWN) != null) {
                json(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "{\"error\":\"session_store_unavailable\","
                        + "\"message\":\"The service is temporarily unavailable. Please try again in a few minutes.\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** Loads the deferred CSRF token on every console request, so the {@code XSRF-TOKEN} cookie reaches the console. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Logout for a single-page console: the platform session ends here (invalidated, CSRF token cleared) and the answer
     * is JSON naming the identity provider's sign-out address (RP-initiated logout with the ID-token hint and the
     * registered post-logout address); the console navigates there. A fetch cannot follow a cross-site redirect.
     */
    static final class JsonOidcLogout extends OidcClientInitiatedLogoutSuccessHandler {

        JsonOidcLogout(ConsoleClientRegistrations registrations, String postLogoutRedirectUri) {
            super(registrations);
            setPostLogoutRedirectUri(postLogoutRedirectUri);
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                Authentication authentication) throws IOException {
            String target = determineTargetUrl(request, response, authentication);
            json(response, HttpServletResponse.SC_OK,
                    "{\"redirect\":\"" + target.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
    }
}
