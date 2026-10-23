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

    /**
     * The longest publish-refusal reason answered. Config-local (config never imports routing): routing's
     * RefusalText bounds each echoed value to 32 characters, and its longest whole answer is 104, so 128 cuts no
     * routing answer and still bounds any other thrower. Cut plainly, no marker - RefusalText adds none either.
     */
    static final int REASON_CAP = 128;
    static final String REFUSED = "the publish was refused";

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
     * ADR-017 Decision 2: a publish listener refused the publish (it rolled back). 400 with the listener's reason;
     * a validation refusal, not a cross-tenant attempt - no denial record, nothing logged above DEBUG.
     * Row 278 T160 (K-C2-1, CWE-209 defence in depth): the thrower sanitises, and this seam does not trust it - the
     * reason loses every ISO control character (CR, LF, TAB, NUL, ESC, DEL ...) and, T160 L-5 (CWE-451/CWE-117),
     * every line or paragraph separator (U+2028, U+2029) and format character (Cf: bidi embeddings and isolates,
     * zero-width characters, BOM, soft hyphen), and is cut to {@value #REASON_CAP} code points - L-4 (CWE-176),
     * never a split surrogate pair; a reason blank after that answers {@value #REFUSED}, never an empty string.
     */
    @ExceptionHandler(InterfacePublishRefused.class)
    public ResponseEntity<Map<String, Object>> publishRefused(InterfacePublishRefused refused) {
        LOG.debug("a publish listener refused the publish; it was rolled back", refused);
        return ResponseEntity.status(400).body(ConfigApiException.body("error", answerable(refused.reason())));
    }

    /** T171 S5-A1: a body that is not JSON (truncated, too deep) is the caller's error, a 400 - never the 500 catch-all. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException unreadable) {
        LOG.debug("the request body could not be read", unreadable);
        return ResponseEntity.status(400).body(ConfigApiException.body("error", "the request body could not be read"));
    }

    /**
     * T171 S5-A1 (review C2): a request the framework refuses before the handler runs - wrong content type (415), a
     * missing required parameter (400), a value of the wrong type (400) - keeps the framework's own status with a
     * static body, never the catch-all 500.
     */
    @ExceptionHandler({org.springframework.web.HttpMediaTypeNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotAcceptableException.class,
        org.springframework.web.HttpRequestMethodNotSupportedException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.beans.TypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> requestNotAccepted(Exception refused) {
        LOG.debug("the request was refused before the handler ran", refused);
        org.springframework.web.ErrorResponse shaped = refused instanceof org.springframework.web.ErrorResponse er
                ? er : null;
        return ResponseEntity.status(shaped == null ? 400 : shaped.getStatusCode().value())
                .headers(shaped == null ? org.springframework.http.HttpHeaders.EMPTY : shaped.getHeaders())
                .body(ConfigApiException.body("error", "the request was not accepted"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException clash) {
        LOG.debug("a unique-value clash refused the configuration write", clash);
        return ResponseEntity.status(409)
                .body(ConfigApiException.body("error", "a uniqueness constraint refused the write"));
    }

    /**
     * The catch-all 500 - unless a publish refusal is in the cause chain (T71 crit 5 / F15): a commit path that
     * wraps the listener's {@link InterfacePublishRefused} still answers the named 400, never the generic 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception failure) {
        for (Throwable cause = failure.getCause(); cause != null && cause != failure; cause = cause.getCause()) {
            if (cause instanceof InterfacePublishRefused refused) {
                return publishRefused(refused);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        LOG.error("the configuration write failed; the transaction was rolled back", failure);
        return ResponseEntity.status(500).body(ConfigApiException.body(
                "error", "the configuration write failed and the transaction was rolled back"));
    }

    /** The reason without control, separator or format characters, at most {@value #REASON_CAP} code points. */
    private static String answerable(String reason) {
        String kept = reason.codePoints().filter(ConfigApiExceptionHandler::answerable).limit(REASON_CAP)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return kept.isBlank() ? REFUSED : kept;
    }

    /** Not an ISO control (Cc), a line or paragraph separator (Zl, Zp) or a format character (Cf). */
    private static boolean answerable(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint) && type != Character.FORMAT
                && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR;
    }

    /** The one denied-attempt record a path-named foreign row earns: bound tenant, method and the raw request path (which carries the id; the recorder neutralises it). */
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
