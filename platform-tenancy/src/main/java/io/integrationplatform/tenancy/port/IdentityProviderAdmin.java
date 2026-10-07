package io.integrationplatform.tenancy.port;

import java.util.Objects;

/**
 * The identity provider's client administration the platform needs for machine credentials (ADR-007 Decision 3, T-186):
 * one source-system credential = one confidential client-credentials client of the platform realm. Implemented by the
 * Keycloak admin adapter (a service account holding only the realm's client-management role); E5 T-103 (issue) and the
 * revoke order (T-186) call it. Every call carries the five controls of R13; a failure is an
 * {@link IdentityProviderAdminException} with a clear message and nothing half-created.
 */
public interface IdentityProviderAdmin {

    /** The issuer whose clients this administers - a credential row of another issuer is never sent here. */
    String issuer();

    /**
     * Creates an enabled confidential client whose default client scopes ALWAYS include the platform API audience scope
     * (ADR-007 Decision 3, S-j), in one call, and returns its secret - shown once, never stored or logged by the
     * platform. A create is never retried: on a failure the caller learns it and nothing is half-created.
     */
    CreatedClient createConfidentialClient(String clientId);

    /** Disables the client; returns when the provider confirms, or when it no longer has the client. */
    void disableClient(String clientId);

    /** A created client; the secret lives in memory only and is never part of {@link #toString()}. */
    record CreatedClient(String clientId, String secret) {

        public CreatedClient {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(secret, "secret");
        }

        @Override
        public String toString() {
            return "CreatedClient[" + clientId + "]";
        }
    }

    /** The identity provider refused or could not be reached; the message names the client, never a secret. */
    final class IdentityProviderAdminException extends RuntimeException {

        public IdentityProviderAdminException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
