package io.integrationplatform.e2e.seed;

/** RED stub (row 327 T157). */
public final class SourceCredentialSeed {

    private SourceCredentialSeed() {
    }

    public static IssuedCredential issue(TenantSeedPlan.SourceCredentialSpec spec, AdminApi tenantBAdmin) {
        throw new UnsupportedOperationException("RED");
    }

    public record IssuedCredential(String credentialId, String systemId, String clientId, String secret) {
    }
}
