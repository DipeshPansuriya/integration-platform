package io.integrationplatform.tenancy.internal.web;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * R13 / ADR-007 Decision 4: the retry, circuit-breaker and key-cache settings of every identity-provider call
 * ({@code platform.tenancy.identity-provider.*} in tenancy-security.yml). The 2 s JWKS timeout is fixed by ADR-007 and
 * is not a setting ({@link TenancySecurityConfiguration#JWKS_TIMEOUT}).
 *
 * @param retryMaxAttempts       attempts per call, the first included; only transient failures are retried
 * @param retryInitialBackoff    wait before the second attempt
 * @param retryMultiplier        growth of the wait per attempt (exponential backoff)
 * @param retryRandomization     jitter: each wait is drawn from +/- this fraction of the exponential value
 * @param retryMaxBackoff        cap on any single wait
 * @param breakerFailureThreshold consecutive failed calls that open the {@code identity-provider} breaker
 * @param breakerWaitInOpen      how long the open breaker refuses calls before one half-open trial
 * @param keySetTtl              how long fetched keys are used before the JWKS is fetched again
 * @param keySetFallbackMaxAge   how long the last fetched keys keep validating while the provider is unreachable
 */
record IdentityProviderSettings(
        @DefaultValue("3") int retryMaxAttempts,
        @DefaultValue("200ms") Duration retryInitialBackoff,
        @DefaultValue("2.0") double retryMultiplier,
        @DefaultValue("0.5") double retryRandomization,
        @DefaultValue("2s") Duration retryMaxBackoff,
        @DefaultValue("5") int breakerFailureThreshold,
        @DefaultValue("30s") Duration breakerWaitInOpen,
        @DefaultValue("5m") Duration keySetTtl,
        @DefaultValue("1h") Duration keySetFallbackMaxAge) {

    static final String PREFIX = "platform.tenancy.identity-provider";

    /** The {@code @DefaultValue}s above - what runs when tenancy-security.yml sets nothing. */
    static IdentityProviderSettings defaults() {
        return new Binder().bindOrCreate(PREFIX, IdentityProviderSettings.class);
    }

    static IdentityProviderSettings from(Environment environment) {
        return Binder.get(environment).bindOrCreate(PREFIX, IdentityProviderSettings.class);
    }
}
