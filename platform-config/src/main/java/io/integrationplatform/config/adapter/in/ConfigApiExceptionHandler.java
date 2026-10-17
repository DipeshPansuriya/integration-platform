package io.integrationplatform.config.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.api.InterfacePublishRefused;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The configuration APIs' error seam: every refusal and every failure leaves as an explicit JSON answer with the
 * status the scenario names. This matters beyond tidiness - the stateless security chain denies the unauthenticated
 * {@code /error} forward, so an exception allowed to propagate would surface as a 403 with an empty body instead
 * of the designed 400/404/409/500 (the RED signature of the first E3 runs). The 500 answer is the publish's
 * rollback report: a static text, no row data, no sentinel echo.
 *
 * <p>The 404 answer is also where the matrix's PATH_ID channel is recorded (E1-F1-S1, control T1): a row the bound
 * tenant cannot see was named in the path, so the attempt is denied evidence - recorded once here, centrally, for
 * every config endpoint, exactly as the test probe records its own miss. The recorder is resolved through an
 * {@link ObjectProvider} because a composition may run this seam without the tenancy web chain (same fail-closed
 * shape the repositories use for the tenant context).
 */
@RestControllerAdvice(basePackages = "io.integrationplatform.config")
public class ConfigApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigApiExceptionHandler.class);

    private final ObjectProvider<TenantDenialRecorder> recorders;
    private final ObjectProvider<TenantContext> tenantContexts;

    public ConfigApiExceptionHandler(ObjectProvider<TenantDenialRecorder> recorders,
            ObjectProvider<TenantContext> tenantContexts) {
        this.recorders = Objects.requireNonNull(recorders, "recorders");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "tenantContexts");
    }

    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<Map<String, Object>> refusal(ConfigApiException refused, HttpServletRequest request) {
        if (refused.httpStatus() == 404) {
            recordPathIdDenial(request);
        }
        return ResponseEntity.status(refused.httpStatus()).body(refused.body());
    }

    /**
     * ADR-017 Decision 2: a publish listener refused the publish (it rolled back). 400 with the listener's sanitised
     * reason; a validation refusal, not a cross-tenant attempt - no denial record, nothing logged above DEBUG.
     */
    @ExceptionHandler(InterfacePublishRefused.class)
    public ResponseEntity<Map<String, Object>> publishRefused(InterfacePublishRefused refused) {
        LOG.debug("a publish listener refused the publish; it was rolled back", refused);
        return ResponseEntity.status(400).body(ConfigApiException.body("error", refused.reason()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException clash) {
        LOG.debug("a unique-value clash refused the configuration write", clash);
        return ResponseEntity.status(409)
                .body(ConfigApiException.body("error", "a uniqueness constraint refused the write"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception failure) {
        LOG.error("the configuration write failed; the transaction was rolled back", failure);
        return ResponseEntity.status(500).body(ConfigApiException.body(
                "error", "the configuration write failed and the transaction was rolled back"));
    }

    /** The one denied-attempt record a path-named foreign row earns: bound tenant, method, path - never the id. */
    private void recordPathIdDenial(HttpServletRequest request) {
        TenantDenialRecorder recorder = recorders.getIfAvailable();
        if (recorder == null) {
            return;
        }
        TenantContext context = tenantContexts.getIfAvailable(() -> () -> Optional.empty());
        UUID bound = context.currentTenantId().orElse(null);
        recorder.record(new DeniedTenantAttempt(bound, DeniedTenantAttempt.Channel.PATH_ID, request.getMethod(),
                request.getRequestURI(), Instant.now()));
    }
}
