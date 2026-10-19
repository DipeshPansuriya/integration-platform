package io.integrationplatform.ingest.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.context.annotation.Configuration;

/**
 * The ingest module's endpoint -> operation rules (T-099): the tenant interceptor decides every {@code /api}
 * request from these, so the controller carries no authorization code. The common intake endpoint is a source
 * system's machine-credential operation ({@code SUBMIT_MESSAGE}, Caller.SOURCE_SYSTEM) - a human tenant admin is
 * refused here exactly like any other non-source principal.
 */
@Configuration(proxyBeanMethods = false)
public class IngestEndpointRoles implements ApiOperationRules {

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("POST", "/api/v1/interfaces/{interfaceCode}/messages", Operation.SUBMIT_MESSAGE),
                new Rule("POST", "/api/v1/interfaces/{interfaceCode}/messages:bulk", Operation.SUBMIT_MESSAGE));
    }
}