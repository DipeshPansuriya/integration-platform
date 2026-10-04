package io.integrationplatform.tenancy.internal.web;

import io.github.resilience4j.core.IntervalFunction;

/** R13 skeleton (red commit): the names the fault tests use; the controls come with the green commit. */
final class IdentityProviderClient {

    static final String NAME = "identity-provider";
    static final String OBSERVATION = "identity.provider.call";

    private IdentityProviderClient() {
    }

    static IntervalFunction backoff(IdentityProviderSettings settings) {
        return IntervalFunction.of(settings.retryInitialBackoff());
    }

    static boolean isTransient(Throwable failure) {
        return false;
    }
}
