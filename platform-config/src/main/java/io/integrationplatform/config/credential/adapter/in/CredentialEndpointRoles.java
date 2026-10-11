package io.integrationplatform.config.credential.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E5 T-103: the role rule of each credential endpoint (E5-F1-S3 "Only a tenant admin manages credentials"). Issuing and
 * rotating a source credential is ISSUE_SOURCE_CREDENTIAL (rotation = issuing a second one, ADR-007 Decision 3);
 * revoking is REVOKE_SOURCE_CREDENTIAL; every target-secret write is MANAGE_TARGET_CREDENTIAL - all TENANT_ADMIN only,
 * so configurator, support and read-only get 403. Reading the masked lists (which credentials and fields exist, never
 * a value) is READ_CONFIGURATION, like the rest of a system's configuration.
 */
@Component
public class CredentialEndpointRoles implements ApiOperationRules {

    static final String SOURCE_OF_SYSTEM = "/api/v1/admin/systems/{systemId}/source-credentials";
    static final String SOURCE_CREDENTIAL = "/api/v1/admin/source-credentials/{credentialId}";
    static final String TARGET_OF_ENDPOINT = "/api/v1/admin/target-endpoints/{endpointId}/credentials";

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("POST", SOURCE_OF_SYSTEM, Operation.ISSUE_SOURCE_CREDENTIAL),
                new Rule("GET", SOURCE_OF_SYSTEM, Operation.READ_CONFIGURATION),
                new Rule("POST", SOURCE_CREDENTIAL + "/rotate", Operation.ISSUE_SOURCE_CREDENTIAL),
                new Rule("POST", SOURCE_CREDENTIAL + "/revoke", Operation.REVOKE_SOURCE_CREDENTIAL),
                new Rule("GET", TARGET_OF_ENDPOINT, Operation.READ_CONFIGURATION),
                new Rule("PUT", TARGET_OF_ENDPOINT, Operation.MANAGE_TARGET_CREDENTIAL));
    }
}
