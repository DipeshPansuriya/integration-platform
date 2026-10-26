package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * T-186 (ADR-007 Decisions 3 and 4, S-j): the Keycloak Admin REST adapter for machine credentials - standard OAuth 2.0
 * client credentials for its own token (the {@code platform-admin} service account of lane D's realm, holding only the
 * realm's client-management roles; its secret by the environment variable NAME {@value #ADMIN_SECRET_VARIABLE}, R07),
 * then the realm's {@code /admin/realms/<realm>/clients} resource.
 *
 * <p>The five controls of R13 on every call: (1) a 5 s timeout ({@link #ADMIN_TIMEOUT}, connect and answer); (2) retry
 * of transient failures of idempotent reads only - the client lookup; a create or disable answered 5xx is never
 * retried (only a connection that was refused - the request never left - is tried again by the shared client); (3) the
 * ONE {@code identity-provider} breaker of the application ({@link IdentityProviderClient}); (4) the fallback: a clear
 * {@link IdentityProviderAdminException} and nothing half-done - a client is created in ONE call that carries its
 * secret and its default scopes, so there is no second step to fail; (5) metrics and a trace span: the observation
 * {@value #OBSERVATION} (operation, outcome) around each operation, plus the shared client's per-call observation and
 * the breaker and retry meters. No secret is ever logged or put in a message (R14).
 */
public final class KeycloakAdminAdapter implements IdentityProviderAdmin {

    /** ADR-007 Decision 4: 5 s for an admin call. */
    public static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(5);

    /** The realm's audience scope (lane D's realm file); assigned to every client the platform creates. */
    public static final String AUDIENCE_SCOPE = "platform-api-audience";

    /** The environment variable that holds the admin client's secret - the name only, never a value (R07). */
    public static final String ADMIN_SECRET_VARIABLE = "PLATFORM_ADMIN_CLIENT_SECRET";

    static final String OBSERVATION = "identity.provider.admin";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String issuer;
    private final URI tokenEndpoint;
    private final String clientsResource;
    private final String adminClientId;
    private final Supplier<String> adminClientSecret;
    private final RestTemplate rest;
    private final ObservationRegistry observations;

    /**
     * @param issuer            the realm's issuer, {@code <base>/realms/<realm>} (OIDC_ISSUER_URI)
     * @param adminBaseUri      optional server address for the admin REST calls only ({@code <base>} of
     *                          {@code <base>/admin/realms/<realm>}), for a deployment whose public edge does not route
     *                          /admin; null or blank = derived from the issuer, as before. The token endpoint and
     *                          {@link #issuer()} always stay on the issuer.
     * @param allowExternal     the explicit opt-in (KEYCLOAK_ADMIN_ALLOW_EXTERNAL) for an https admin base on a host other
     *                          than the issuer host; default off. The admin base itself is checked by {@link AdminBaseUri}
     *                          and an unsafe value stops the start-up.
     * @param adminClientId     the service-account client ({@code platform-admin})
     * @param adminClientSecret its secret, read when a token is needed and held only for that call
     * @param rest              a client from {@link #restTemplate} (the shared breaker, the admin timeout)
     */
    public KeycloakAdminAdapter(String issuer, String adminClientId, Supplier<String> adminClientSecret,
            RestTemplate rest, ObservationRegistry observations) {
        this(issuer, null, adminClientId, adminClientSecret, rest, observations);
    }

    public KeycloakAdminAdapter(String issuer, String adminBaseUri, String adminClientId,
            Supplier<String> adminClientSecret, RestTemplate rest, ObservationRegistry observations) {
        this(issuer, adminBaseUri, false, adminClientId, adminClientSecret, rest, observations);
    }

    public KeycloakAdminAdapter(String issuer, String adminBaseUri, boolean allowExternal, String adminClientId,
            Supplier<String> adminClientSecret, RestTemplate rest, ObservationRegistry observations) {
        // The issuer is kept exactly as configured: revoke and disable compare it with the stored one. Only the URLs
        // built from it use a slash-trimmed copy; the slash stripping is for the admin base alone.
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        String realmUri = stripSlash(this.issuer);
        int realms = realmUri.lastIndexOf("/realms/");
        if (realms < 0) {
            throw new IllegalArgumentException("The issuer is not a Keycloak realm URI: " + this.issuer);
        }
        this.tokenEndpoint = URI.create(realmUri + "/protocol/openid-connect/token");
        String adminBase = AdminBaseUri.resolve(adminBaseUri, this.issuer, allowExternal);
        this.clientsResource = (adminBase != null ? adminBase : realmUri.substring(0, realms))
                + "/admin" + realmUri.substring(realms) + "/clients";
        this.adminClientId = Objects.requireNonNull(adminClientId, "adminClientId");
        this.adminClientSecret = Objects.requireNonNull(adminClientSecret, "adminClientSecret");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /**
     * The admin client: the 5 s admin timeout around the application's ONE identity-provider breaker and retry (the
     * interceptor the discovery, JWKS and console token calls share, bound per meter registry).
     */
    public static RestTemplate restTemplate(IdentityProviderSettings settings, MeterRegistry meters,
            ObservationRegistry observations) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(ADMIN_TIMEOUT).build());
        requests.setReadTimeout(ADMIN_TIMEOUT);
        RestTemplate rest = new RestTemplate(requests);
        // ponytail: the shared interceptor is taken from the client IdentityProviderClient builds, so the breaker and
        // retry are the very same instances; only the timeout differs (5 s admin vs 2 s JWKS, ADR-007 Decision 4).
        rest.setInterceptors(IdentityProviderClient.restTemplate(settings, meters, observations).getInterceptors());
        return rest;
    }

    @Override
    public String issuer() {
        return issuer;
    }

    @Override
    public CreatedClient createConfidentialClient(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        return observe("create_client", () -> {
            String secret = newSecret();
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("clientId", clientId);
            client.put("enabled", true);
            client.put("protocol", "openid-connect");
            client.put("publicClient", false);
            client.put("clientAuthenticatorType", "client-secret");
            client.put("secret", secret);
            client.put("serviceAccountsEnabled", true);
            client.put("standardFlowEnabled", false);
            client.put("implicitFlowEnabled", false);
            client.put("directAccessGrantsEnabled", false);
            // S-j: named explicitly, never left to the realm's defaults - a client the platform creates always gets
            // a token whose aud names the platform API.
            client.put("defaultClientScopes", List.of("basic", AUDIENCE_SCOPE));
            try {
                rest.exchange(URI.create(clientsResource), HttpMethod.POST, json(client, adminToken()), Void.class);
            } catch (HttpClientErrorException.Conflict exists) {
                throw new IdentityProviderAdminException("The client " + clientId + " already exists", null);
            } catch (RestClientException failure) {
                throw new IdentityProviderAdminException("The identity provider did not create the client "
                        + clientId + "; nothing was created on the platform side - try again later", failure);
            }
            return new CreatedClient(clientId, secret);
        });
    }

    @Override
    public void disableClient(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        observe("disable_client", () -> {
            try {
                String token = adminToken();
                URI lookup = UriComponentsBuilder.fromUriString(clientsResource).queryParam("clientId", clientId)
                        .build().encode().toUri();
                ResponseEntity<List<Map<String, Object>>> found = rest.exchange(lookup, HttpMethod.GET,
                        json(null, token), new org.springframework.core.ParameterizedTypeReference<>() { });
                List<Map<String, Object>> clients = found.getBody() == null ? List.of() : found.getBody();
                for (Map<String, Object> client : clients) {
                    if (!clientId.equals(client.get("clientId"))) {
                        continue;
                    }
                    Map<String, Object> disabled = new LinkedHashMap<>();
                    disabled.put("clientId", clientId);
                    disabled.put("enabled", false);
                    rest.exchange(URI.create(clientsResource + "/" + client.get("id")), HttpMethod.PUT,
                            json(disabled, token), Void.class);
                }
                return null;
            } catch (RestClientException failure) {
                throw new IdentityProviderAdminException("The identity provider did not disable the client "
                        + clientId + " - it stays pending for the credential reconciliation", failure);
            }
        });
    }

    /** A client-credentials token of the admin service account; the secret leaves memory only in this form post. */
    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret.get());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<Map<String, Object>> answer = rest.exchange(tokenEndpoint, HttpMethod.POST,
                    new HttpEntity<>(form, headers), new org.springframework.core.ParameterizedTypeReference<>() { });
            Object token = answer.getBody() == null ? null : answer.getBody().get("access_token");
            if (!(token instanceof String value) || value.isBlank()) {
                throw new IdentityProviderAdminException("The identity provider answered no admin token", null);
            }
            return value;
        } catch (RestClientException failure) {
            // the cause carries the provider's status only; the form (and so the secret) is never in a message
            throw new IdentityProviderAdminException("The identity provider refused or could not issue the admin token",
                    failure);
        }
    }

    private static HttpEntity<Object> json(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private <T> T observe(String operation, Supplier<T> call) {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("operation", operation)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            T result = call.get();
            observation.lowCardinalityKeyValue("outcome", "success");
            return result;
        } catch (RuntimeException failure) {
            observation.lowCardinalityKeyValue("outcome", "error");
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    /** 256 random bits, URL-safe base64 (43 characters) - generated here so the create is one call. */
    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripSlash(String uri) {
        int end = uri.length();
        while (end > 0 && uri.charAt(end - 1) == '/') {
            end--;
        }
        return uri.substring(0, end);
    }
}
