package io.integrationplatform.config.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@RestControllerAdvice(basePackages = "io.integrationplatform.config")
public class ConfigApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigApiExceptionHandler.class);

    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<Map<String, Object>> refusal(ConfigApiException refused) {
        return ResponseEntity.status(refused.httpStatus()).body(refused.body());
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
}
