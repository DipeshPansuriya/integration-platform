package io.integrationplatform.app.web;

import io.integrationplatform.config.application.ConfigApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T208 / O1 (row 372): this module's own error seam. Without it an exception leaves the controller as a container
 * error (the {@code /error} dispatch): the framework's default body, and 403 with an empty body whenever the chain
 * does not open that dispatch (it did not before R1, 40052c4d). Here every failure is an explicit JSON answer:
 * {@code {"error": "<fixed text>"}}, one fixed text per status class, never an exception message, value or stack.
 *
 * <p>Scope and order (T208): exactly this module's root package, {@code @Order(0)}; the one platform-wide fallback
 * that may ever exist lives here too, at LOWEST_PRECEDENCE (ArchUnit {@code ExceptionAdviceScopedToOwnModuleRule}).
 * A controller's own {@code @ExceptionHandler} methods still win over any advice (Spring rule). Security-chain
 * answers (401/403, the session-store 503) are produced by filters and never reach this advice.
 *
 * <p>Status: a {@link ConfigApiException} (thrown by the shared {@code ConfigActors}, e.g. 403 NO_BOUND_USER) keeps
 * its own status and its static body; an {@link ErrorResponse} (405, 406, 415, a missing parameter, a
 * {@code ResponseStatusException}, ...) keeps its own status and headers; a body that cannot be read or a value of
 * the wrong type is 400; anything else is 500. The log carries the exception class only.
 */
@RestControllerAdvice(basePackages = "io.integrationplatform.app")
@Order(0)
public class AppApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AppApiExceptionHandler.class);

    static final String CLIENT_ERROR = "the request was not accepted";
    static final String SERVER_ERROR = "the request failed";

    /** The config API's own refusal: its status and static body are the contract (docs list 403 NO_BOUND_USER). */
    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<Map<String, Object>> refusal(ConfigApiException refused) {
        LOG.debug("the request was refused with {} ({})", refused.httpStatus(), refused.getClass().getName());
        return ResponseEntity.status(refused.httpStatus()).body(refused.body());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> answer(Exception failure) {
        HttpStatusCode status = HttpStatusCode.valueOf(400);
        HttpHeaders headers = HttpHeaders.EMPTY;
        if (failure instanceof ErrorResponse shaped) {
            status = shaped.getStatusCode();
            headers = shaped.getHeaders();
        } else if (!(failure instanceof TypeMismatchException)
                && !(failure instanceof HttpMessageNotReadableException)) {
            status = HttpStatusCode.valueOf(500);
        }
        if (status.is5xxServerError()) {
            LOG.error("the request failed with {} ({})", status.value(), failure.getClass().getName());
        } else {
            LOG.debug("the request was refused with {} ({})", status.value(), failure.getClass().getName());
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", status.is5xxServerError() ? SERVER_ERROR : CLIENT_ERROR));
    }
}
