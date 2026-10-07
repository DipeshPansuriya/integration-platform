package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.RolePolicy;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.internal.principal.SourceBoundAuthentication;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import io.integrationplatform.tenancy.port.RoleGrants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * T-099 (ADR-007 Decision 5): the server-side role check of every {@code /api} handler, for the bearer chain and the
 * console session chain alike - both bind a {@link TenantBoundAuthentication} through the same resolver before a
 * handler is chosen. The handler's mapping (method + pattern) is looked up in the modules' {@link ApiOperationRules};
 * the operation's {@link RolePolicy} row decides:
 *
 * <ul>
 *   <li>a role-gated operation needs a bound human holding one of its roles - read from {@code role_grant} in a
 *       tenant-bound transaction (R11), never from the token;</li>
 *   <li>{@code READ_OWN_IDENTITY} needs any bound human; {@code SUBMIT_MESSAGE} a source system's machine credential;</li>
 *   <li>a machine (source-bound) principal holds no human role: every role-gated operation refuses it.</li>
 * </ul>
 *
 * A refusal is 403 {@code {"refusal":"ROLE_NOT_GRANTED"}} (the config error body's shape) and one structured WARN event
 * {@code event=role.denied} (operation, method, path, tenant, user - never a token, R14); the handler never runs. A
 * handler with no rule is not judged here: every main-source {@code /api} handler has one, which platform-app's
 * {@code ApiOperationRulesCompletenessTest} enforces at build time (test-only probes have none).
 */
final class RoleEnforcementInterceptor implements HandlerInterceptor {

    static final String REFUSAL_BODY = "{\"refusal\":\"ROLE_NOT_GRANTED\"}";

    private static final Logger LOG = LoggerFactory.getLogger(RoleEnforcementInterceptor.class);
    private static final int MAX_PATH = 512;

    private final Map<String, Operation> operations;
    private final RoleGrants roleGrants;
    private final TransactionOperations transactions;

    /**
     * @throws IllegalStateException when two rule sets name one endpoint - a start-up failure, never a silent pick
     */
    RoleEnforcementInterceptor(Collection<ApiOperationRules> ruleSets, RoleGrants roleGrants,
            TransactionOperations transactions) {
        Map<String, Operation> byKey = new LinkedHashMap<>();
        for (ApiOperationRules set : ruleSets) {
            for (Rule rule : set.rules()) {
                if (byKey.putIfAbsent(rule.key(), rule.operation()) != null) {
                    throw new IllegalStateException("Two role rules name one endpoint: " + rule.key());
                }
            }
        }
        this.operations = Map.copyOf(byKey);
        this.roleGrants = roleGrants;
        this.transactions = transactions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Operation operation = pattern == null ? null : operations.get(request.getMethod() + " " + pattern);
        if (operation == null) {
            return true;
        }
        Authentication caller = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (allowed(operation, caller)) {
            return true;
        }
        refuse(operation, caller, request, response);
        return false;
    }

    private boolean allowed(Operation operation, Authentication caller) {
        return switch (operation.caller()) {
            case SOURCE_SYSTEM -> caller instanceof SourceBoundAuthentication;
            case ANY_HUMAN -> caller instanceof TenantBoundAuthentication;
            case ROLE -> caller instanceof TenantBoundAuthentication human
                    && RolePolicy.allows(operation, rolesOf(human.binding().userId()));
        };
    }

    /** The bound human's roles, read in a tenant-bound transaction (the stamp comes from the bound tenant). */
    private Set<Role> rolesOf(UUID userId) {
        if (roleGrants == null) {
            return EnumSet.noneOf(Role.class); // no directory wired: no role, fail closed
        }
        Set<Role> roles = transactions.execute(status -> roleGrants.rolesOf(userId));
        return roles == null ? EnumSet.noneOf(Role.class) : roles;
    }

    private static void refuse(Operation operation, Authentication caller, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        UUID tenant = null;
        UUID user = null;
        if (caller instanceof TenantBoundAuthentication human) {
            tenant = human.binding().tenantId();
            user = human.binding().userId();
        } else if (caller instanceof SourceBoundAuthentication source) {
            tenant = source.binding().tenantId();
        }
        String path = clean(request.getRequestURI());
        LOG.atWarn()
                .addKeyValue("event", "role.denied")
                .addKeyValue("operation", operation)
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", path)
                .addKeyValue("boundTenant", tenant)
                .addKeyValue("user", user)
                .log("event=role.denied operation={} method={} path={} boundTenant={} user={}",
                        operation, request.getMethod(), path, tenant, user);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(REFUSAL_BODY);
    }

    /** No line breaks or control characters from the request reach the log (log forging), and the length is capped. */
    private static String clean(String value) {
        String flat = value.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > MAX_PATH ? flat.substring(0, MAX_PATH) : flat;
    }
}
