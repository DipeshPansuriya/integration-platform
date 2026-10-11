package io.integrationplatform.tenancy.e5.credential;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * E5 T-103 test double of the identity-provider admin port: records every create and disable, can be told to fail
 * (the provider down or timing out), and runs a probe during a create so a test can look at the platform row at the
 * moment the provider is called (ADR-007 Decision 3: the row is PENDING then).
 */
final class RecordingIdentityProvider implements IdentityProviderAdmin {

    private static final SecureRandom RANDOM = new SecureRandom();

    final List<String> created = new ArrayList<>();
    final List<String> disabled = new ArrayList<>();
    final List<String> secrets = new ArrayList<>();
    private final String issuer;
    boolean failCreate;
    boolean failDisable;
    Consumer<String> duringCreate = clientId -> { };

    RecordingIdentityProvider(String issuer) {
        this.issuer = issuer;
    }

    @Override
    public String issuer() {
        return issuer;
    }

    @Override
    public CreatedClient createConfidentialClient(String clientId) {
        duringCreate.accept(clientId);
        if (failCreate) {
            throw new IdentityProviderAdminException("The identity provider did not create the client " + clientId
                    + "; nothing was created on the platform side - try again later", null);
        }
        created.add(clientId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        secrets.add(secret);
        return new CreatedClient(clientId, secret);
    }

    @Override
    public void disableClient(String clientId) {
        if (failDisable) {
            throw new IdentityProviderAdminException("The identity provider did not disable the client " + clientId,
                    null);
        }
        disabled.add(clientId);
    }
}
