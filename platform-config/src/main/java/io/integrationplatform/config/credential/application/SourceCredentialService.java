package io.integrationplatform.config.credential.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.IdentityProviderAdminException;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.CredentialStateException;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.Issued;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.RevokeOutcome;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.Rotated;
import io.integrationplatform.tenancy.port.SourceCredentialStore.Credential;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

/**
 * E5-F1-S3 (T-103; ADR-007 Decision 3): the source-credential API's rules on top of the tenancy module's lifecycle port
 * ({@link SourceCredentialAdmin}): the system must be the bound tenant's (404 otherwise, the row is never hinted at) and a
 * source system (SOURCE or BOTH, 409 otherwise); the customer identity the credential carries is required (400). The
 * secret is in the issue and rotate answers only - once; every read shows the masked marker. An identity-provider
 * failure is 503 with the provider's clear message (nothing half-created - the lifecycle's guarantee). Every action is
 * audited with ids only ({@link CredentialAudit}).
 */
public class SourceCredentialService {

    static final int MAX_CUSTOMER_LENGTH = 200;
    private static final Set<String> SOURCE_DIRECTIONS = Set.of("SOURCE", "BOTH");

    private final JdbcSystemRepository systems;
    private final ObjectProvider<SourceCredentialAdmin> admins;
    private final TransactionOperations readOnly;
    private final CredentialAudit audit;

    public SourceCredentialService(JdbcSystemRepository systems, ObjectProvider<SourceCredentialAdmin> admins,
            TransactionOperations readOnly, CredentialAudit audit) {
        this.systems = Objects.requireNonNull(systems, "systems");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.readOnly = Objects.requireNonNull(readOnly, "readOnly");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Map<String, Object> issue(String systemId, JsonNode body, LoginBinding actor) {
        UUID system = requireSourceSystem(systemId);
        String customerId = customer(body);
        SourceCredentialAdmin admin = admin();
        Issued issued;
        try {
            issued = admin.issue(system, customerId);
        } catch (IdentityProviderAdminException failed) {
            audit.sourceRefused(actor.tenantId(), actor.userId(), system, "identity_provider_unavailable");
            throw CredentialApiException.unavailable(failed.getMessage());
        }
        audit.sourceIssued(actor.tenantId(), actor.userId(), system, issued.credentialId(), issued.clientId());
        return answer(issued);
    }

    public List<Map<String, Object>> list(String systemId) {
        UUID system = requireSourceSystem(systemId);
        return admin().list(system).stream().map(SourceCredentialService::masked).toList();
    }

    public Map<String, Object> rotate(String credentialId, LoginBinding actor) {
        UUID id = parse(credentialId);
        Rotated rotated;
        try {
            rotated = admin().rotate(id);
        } catch (CredentialStateException refused) {
            throw switch (refused.reason()) {
                case NOT_FOUND -> ConfigApiException.notFound();
                case NOT_ACTIVE -> ConfigApiException.conflict("only an ACTIVE credential can be rotated");
                case ALREADY_ROTATING -> ConfigApiException.conflict("the credential is already being rotated");
            };
        } catch (IdentityProviderAdminException failed) {
            audit.sourceRefused(actor.tenantId(), actor.userId(), null, "identity_provider_unavailable");
            throw CredentialApiException.unavailable(failed.getMessage());
        }
        audit.sourceRotated(actor.tenantId(), actor.userId(), id, rotated.replacement().credentialId());
        Map<String, Object> answer = answer(rotated.replacement());
        answer.put("replaces", id.toString());
        answer.put("replacedRevokeAfter", rotated.replacedRevokeAfter().toString());
        return answer;
    }

    public Map<String, Object> revoke(String credentialId, LoginBinding actor) {
        UUID id = parse(credentialId);
        RevokeOutcome outcome = admin().revoke(id);
        return switch (outcome) {
            case NOT_FOUND -> throw ConfigApiException.notFound();
            case ALREADY_REVOKED -> throw ConfigApiException.conflict("the credential is already revoked");
            case REVOKED, IDP_DISABLE_PENDING -> {
                audit.sourceRevoked(actor.tenantId(), actor.userId(), id, outcome.name());
                yield ConfigApiException.body("id", id.toString(), "status", "REVOKED",
                        "idpDisablePending", outcome == RevokeOutcome.IDP_DISABLE_PENDING);
            }
        };
    }

    private UUID requireSourceSystem(String systemId) {
        UUID system = parse(systemId);
        SystemDetail detail = readOnly.execute(status -> systems.findSystem(system.toString()))
                .orElseThrow(ConfigApiException::notFound);
        if (!SOURCE_DIRECTIONS.contains(detail.direction())) {
            throw ConfigApiException.conflict("credentials are issued for source (INBOUND) systems only");
        }
        return system;
    }

    private SourceCredentialAdmin admin() {
        SourceCredentialAdmin admin = admins.getIfAvailable();
        if (admin == null) {
            throw CredentialApiException.unavailable("source credential administration is not available on this"
                    + " instance");
        }
        return admin;
    }

    private static String customer(JsonNode body) {
        JsonNode value = body == null ? null : body.get("customerId");
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || value.stringValue().strip().length() > MAX_CUSTOMER_LENGTH) {
            throw CredentialApiException.invalid(List.of("customerId is required: the customer identity the"
                    + " source's calls carry (a non-empty text of at most " + MAX_CUSTOMER_LENGTH + " characters)"));
        }
        return value.stringValue().strip();
    }

    /** The issue/rotate answer: the one place the secret is ever shown. */
    private static Map<String, Object> answer(Issued issued) {
        return ConfigApiException.body("id", issued.credentialId().toString(),
                "systemId", issued.sourceSystemId().toString(), "customerId", issued.customerId(),
                "clientId", issued.clientId(), "status", "ACTIVE", "secret", issued.secret());
    }

    private static Map<String, Object> masked(Credential credential) {
        return ConfigApiException.body("id", credential.id().toString(),
                "systemId", credential.sourceSystemId().toString(), "customerId", credential.customerId(),
                "clientId", credential.clientId(), "status", credential.status(),
                "idpDisablePending", credential.idpDisablePending(),
                "createdAt", text(credential.createdAt()), "revokedAt", text(credential.revokedAt()),
                "revokeAfter", text(credential.revokeAfter()), "secret", TargetCredentialService.MASK);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw ConfigApiException.notFound();
        }
    }
}
