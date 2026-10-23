package io.integrationplatform.delivery.web;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T208 / O1 (row 372): this module's own error seam. Without it an exception leaves the controller as a container
 * error (the {@code /error} dispatch): the framework's default body, and 403 with an empty body whenever the chain
 * does not open that dispatch (it did not before R1, 40052c4d). Here every failure is an explicit JSON answer:
 * {@code {"error": "<fixed text>"}}, one fixed text per status class, never an exception message, value or stack.
 *
 * <p>Scope and order (T208): exactly this module's root package, {@code @Order(0)}; a platform-wide fallback may
 * live only in platform-app at LOWEST_PRECEDENCE (ArchUnit {@code ExceptionAdviceScopedToOwnModuleRule}). A
 * controller's own {@code @ExceptionHandler} methods still win over any advice (Spring rule).
 *
 * <p>Status: an {@link ErrorResponse} (405, 406, 415, a missing parameter, a {@code ResponseStatusException}, ...)
 * keeps its own status and headers; a body that cannot be read or a value of the wrong type is 400; anything else is
 * 500. The log carries the exception class only.
 */
@RestControllerAdvice(basePackages = "io.integrationplatform.delivery")
@Order(0)
public class DeliveryApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryApiExceptionHandler.class);

    static final String CLIENT_ERROR = "the request was not accepted";
    static final String SERVER_ERROR = "the request failed";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> answer(Exception failure) {
        // F3 (Karan L-3): a security exception is Spring Security's to answer (403 / 401), never the fixed 500
        if (failure instanceof AccessDeniedException denied) {
            throw denied;
        }
        if (failure instanceof AuthenticationException unauthenticated) {
            throw unauthenticated;
        }
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
