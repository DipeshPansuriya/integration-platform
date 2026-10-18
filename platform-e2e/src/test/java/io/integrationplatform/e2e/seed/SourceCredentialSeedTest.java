package io.integrationplatform.e2e.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.e2e.seed.AdminApi.Response;
import io.integrationplatform.e2e.seed.SourceCredentialSeed.IssuedCredential;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Row 327 T157: tenant B's source credential is issued through the REAL admin API (SystemAdminController, then
 * SourceCredentialAdminController) as tenant B's admin - never a migration, realm import or profile. No Docker: the
 * API answers are scripted, the requests are recorded.
 */
class SourceCredentialSeedTest {

    private final TenantSeedPlan plan = TenantSeedPlan.tenantB("demo-tenant");

    @Test
    void registersTheSourceSystemThenIssuesItsCredentialAsTenantBsAdmin() {
        ScriptedApi api = new ScriptedApi(
                new Response(201, "{\"id\":\"sys-1\",\"code\":\"x\"}"),
                new Response(201, "{\"id\":\"cred-1\",\"systemId\":\"sys-1\",\"clientId\":\"src-abc\","
                        + "\"status\":\"ACTIVE\",\"secret\":\"generated-by-keycloak\"}"));

        IssuedCredential issued = SourceCredentialSeed.issue(plan.credential(), api);

        assertThat(api.calls).containsExactly(
                "POST /api/v1/admin/systems {\"code\":\"" + plan.credential().systemCode() + "\",\"name\":\""
                        + plan.credential().systemName() + "\",\"direction\":\"INBOUND\"}",
                "POST /api/v1/admin/systems/sys-1/source-credentials {\"customerId\":\""
                        + plan.credential().customerId() + "\"}");
        assertThat(issued.credentialId()).isEqualTo("cred-1");
        assertThat(issued.systemId()).isEqualTo("sys-1");
        assertThat(issued.clientId()).isEqualTo("src-abc");
        assertThat(issued.secret()).isEqualTo("generated-by-keycloak");
        assertThat(issued.toString()).contains("src-abc").doesNotContain("generated-by-keycloak");
    }

    @Test
    void aRefusedSystemRegistrationStopsTheSeedBeforeAnyCredential() {
        ScriptedApi api = new ScriptedApi(new Response(403, "{\"error\":\"NO_BOUND_USER\"}"));

        assertThatThrownBy(() -> SourceCredentialSeed.issue(plan.credential(), api))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("403");
        assertThat(api.calls).hasSize(1);
    }

    @Test
    void aRefusedCredentialIssueFailsTheSeed() {
        ScriptedApi api = new ScriptedApi(new Response(201, "{\"id\":\"sys-1\"}"), new Response(503, "{}"));

        assertThatThrownBy(() -> SourceCredentialSeed.issue(plan.credential(), api))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
    }

    /** Answers in order and records "METHOD path body". */
    private static final class ScriptedApi implements AdminApi {

        private final Deque<Response> answers;
        private final List<String> calls = new ArrayList<>();

        ScriptedApi(Response... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public Response send(String method, String path, String jsonBody) {
            calls.add(method + " " + path + " " + jsonBody);
            return answers.removeFirst();
        }
    }
}
