package io.integrationplatform.e2e.seed;

import com.jayway.jsonpath.JsonPath;
import io.integrationplatform.e2e.seed.AdminApi.Response;
import io.integrationplatform.e2e.seed.TenantSeedPlan.SourceCredentialSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minidev.json.JSONValue;

/**
 * Row 327 T157: tenant B's source credential, issued through the REAL admin API as tenant B's admin - the source
 * system first ({@code POST /api/v1/admin/systems}, SystemAdminController), then its client-credentials credential
 * ({@code POST /api/v1/admin/systems/{id}/source-credentials}, SourceCredentialAdminController), which makes the
 * confidential Keycloak client. The secret is the one the platform answers once; it stays in memory.
 */
public final class SourceCredentialSeed {

    private static final String SYSTEMS = "/api/v1/admin/systems";

    private SourceCredentialSeed() {
    }

    /** Registers the source system and issues its credential; any answer but 201 stops the seed. */
    public static IssuedCredential issue(SourceCredentialSpec spec, AdminApi tenantBAdmin) {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("code", spec.systemCode());
        system.put("name", spec.systemName());
        system.put("direction", "INBOUND");
        String systemId = JsonPath.read(created(tenantBAdmin, SYSTEMS, system, "registering the source system"), "$.id");

        String body = created(tenantBAdmin, SYSTEMS + "/" + systemId + "/source-credentials",
                Map.of("customerId", spec.customerId()), "issuing the source credential");
        return new IssuedCredential(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.systemId"),
                JsonPath.read(body, "$.clientId"), JsonPath.read(body, "$.secret"));
    }

    private static String created(AdminApi api, String path, Map<String, Object> body, String step) {
        Response answer = api.send("POST", path, JSONValue.toJSONString(body));
        if (answer.status() != 201) {
            throw new IllegalStateException("tenant B seed: " + step + " answered " + answer.status() + " (expected 201)");
        }
        return answer.body();
    }

    /** The issued credential; the secret is never printed. */
    public record IssuedCredential(String credentialId, String systemId, String clientId, String secret) {

        @Override
        public String toString() {
            return "IssuedCredential[credentialId=" + credentialId + ", systemId=" + systemId + ", clientId=" + clientId
                    + ", secret=********]";
        }
    }
}
