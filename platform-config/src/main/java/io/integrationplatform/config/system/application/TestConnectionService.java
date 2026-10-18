package io.integrationplatform.config.system.application;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.integrationplatform.config.destination.OutboundHttpCheck;
import io.integrationplatform.config.destination.OutboundHttpCheck.Result;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-104 test-connection: is a registered system reachable? Only API systems are tested in this phase - through the one
 * outbound destination guard and the R13-controlled HTTP check ({@link OutboundHttpCheck}); the other types answer that
 * their test arrives with their adapter (MQ through {@link MqConnectionCheck} when wired, E10; DB, File and Kafka topic
 * in E16).
 *
 * <p>Order: the system is read in a short read-only transaction (404 when the bound tenant cannot see it - never a
 * hint whose row it is), then the per tenant + user rate limit (429 {@code TEST_CONNECTION_RATE_LIMITED}), then the
 * check - outside any transaction, so no database connection is held while a destination answers. Every attempt and
 * every refusal is audited ({@link TestConnectionAudit}).
 */
public class TestConnectionService {

    static final String RATE_LIMITED = "TEST_CONNECTION_RATE_LIMITED";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcSystemRepository systems;
    private final TransactionOperations readOnly;
    private final OutboundHttpCheck check;
    private final RateLimiterRegistry rateLimiters;
    private final TestConnectionAudit audit;
    private final MqConnectionCheck mq;

    /**
     * @param limit  test-connection attempts allowed per tenant + user in each {@code window}
     */
    public TestConnectionService(JdbcSystemRepository systems, TransactionOperations readOnly, OutboundHttpCheck check,
            int limit, Duration window, TestConnectionAudit audit) {
        this(systems, readOnly, check, limit, window, audit, null);
    }

    /** @param mq the MQ adapter's check (T-126); {@code null} when no MQ adapter is wired - MQ is then not available */
    public TestConnectionService(JdbcSystemRepository systems, TransactionOperations readOnly, OutboundHttpCheck check,
            int limit, Duration window, TestConnectionAudit audit, MqConnectionCheck mq) {
        this.mq = mq;
        this.systems = Objects.requireNonNull(systems, "systems");
        this.readOnly = Objects.requireNonNull(readOnly, "readOnly");
        this.check = Objects.requireNonNull(check, "check");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.rateLimiters = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(limit)
                .limitRefreshPeriod(Objects.requireNonNull(window, "window"))
                .timeoutDuration(Duration.ZERO) // over the limit: refused at once, never queued
                .build());
    }

    public Map<String, Object> test(String systemId, UUID tenant, UUID actor) {
        if (!SystemAdminService.isUuid(systemId)) {
            throw ConfigApiException.notFound();
        }
        SystemDetail system = Optional.ofNullable(readOnly.execute(status -> systems.findSystem(systemId)))
                .flatMap(found -> found)
                .orElseThrow(ConfigApiException::notFound);
        RateLimiter limiter = rateLimiters.rateLimiter(tenant + ":" + actor);
        if (!limiter.acquirePermission()) {
            audit.record(tenant, actor, system.id(), system.type(), "RATE_LIMITED", RATE_LIMITED, null);
            throw ConfigApiException.refusal(429, RATE_LIMITED);
        }
        String type = system.type();
        if (type == null) {
            audit.record(tenant, actor, system.id(), null, "NOT_CONFIGURED", null, null);
            return answer(system, "NOT_CONFIGURED", null, null, null, null,
                    "The system has no type and settings yet; register them first");
        }
        if (!type.equals("API")) {
            String epic = type.equals("MQ") ? "E10" : "E16";
            audit.record(tenant, actor, system.id(), type, "NOT_AVAILABLE_YET", null, null);
            return answer(system, "NOT_AVAILABLE_YET", null, null, null, null,
                    "The " + type + " connection test arrives with its adapter in " + epic);
        }
        JsonNode settings = JSON.readTree(system.settings());
        URI address;
        try {
            address = new URI(settings.path("baseUrl").asString("").trim());
        } catch (URISyntaxException malformed) {
            audit.record(tenant, actor, system.id(), type, "FAILED", "BAD_ADDRESS", null);
            return answer(system, "FAILED", null, null, "BAD_ADDRESS", null, "The stored base address is malformed");
        }
        boolean followRedirects = settings.path("followRedirects").asBoolean(false);
        Duration timeout = settings.path("timeoutMs").canConvertToInt()
                ? Duration.ofMillis(settings.path("timeoutMs").intValue()) : null;

        Result result = check.check(address, followRedirects, timeout);

        audit.record(tenant, actor, system.id(), type, result.outcome().name(), result.reason(), result.httpStatus());
        return answer(system, result.outcome().name(), result.httpStatus(), result.responseTimeMs(), result.reason(),
                result.detail(), message(result));
    }

    private static String message(Result result) {
        return switch (result.outcome()) {
            case SUCCESS -> "The destination answered HTTP " + result.httpStatus() + " in " + result.responseTimeMs()
                    + " ms";
            case REFUSED -> "Refused before any data was sent: " + result.reason();
            case FAILED -> "The destination could not be tested: " + result.reason();
        };
    }

    /** The answer's fixed shape: every key is always present (JSON null where it does not apply). */
    private static Map<String, Object> answer(SystemDetail system, String result, Integer httpStatus,
            Long responseTimeMs, String reason, String detail, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemId", system.id());
        body.put("type", system.type());
        body.put("result", result);
        body.put("httpStatus", httpStatus);
        body.put("responseTimeMs", responseTimeMs);
        body.put("reason", reason);
        body.put("detail", detail);
        body.put("message", message);
        return body;
    }
}
