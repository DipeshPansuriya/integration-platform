package io.integrationplatform.tenancy.port;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One module's mapping of its {@code /api} endpoints to {@link io.integrationplatform.tenancy.RolePolicy} operations
 * (T-099): the module's controllers stay free of role code, and the tenancy module's interceptor decides every request
 * from these rules for both the bearer and the console session chain. Each module contributes one bean; an endpoint
 * named twice fails the start-up, and a completeness test (platform-app) fails when a main-source {@code /api} handler
 * has no rule.
 */
@FunctionalInterface
public interface ApiOperationRules {

    List<Rule> rules();

    /**
     * @param method  the HTTP method, upper case
     * @param pattern the handler's mapping pattern exactly as Spring MVC registers it, e.g. {@code /api/v1/admin/flows/{flowId}}
     */
    record Rule(String method, String pattern, Operation operation) {

        public Rule {
            method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(operation, "operation");
        }

        /** {@code METHOD pattern} - the key the interceptor looks a request up by. */
        public String key() {
            return method + " " + pattern;
        }
    }
}
