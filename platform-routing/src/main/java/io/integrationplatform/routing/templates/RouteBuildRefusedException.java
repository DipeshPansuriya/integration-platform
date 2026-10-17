package io.integrationplatform.routing.templates;

import io.integrationplatform.config.application.ConfigApiException;

/**
 * The refusal of a route row (E9-F1-S2 @negative @security, T-120 machinery, E9-C6): a route whose configured
 * endpoint URI scheme is outside {@code allowedSchemes}, or whose target type has no template, is never built and
 * the publish fails with a clear error - configuration cannot reach an unapproved component (ADR-010 section 3).
 *
 * <p>Row 278 T106, Kavita F15: a {@link ConfigApiException}, so a refused publish answers through the configuration
 * API's error seam as a 400 naming the refused scheme or type - never the endpoint URI (a URI can carry userinfo).
 * Row 278 B22 (Kavita M1/L1): both the answer and {@link #getMessage()} (the detail logs and the stage 2 dead-letter
 * reason read) carry only {@link RefusalText}-bounded scheme or type text plus the route and endpoint ids - never the
 * endpoint URI, its userinfo, a query token or CR/LF.
 */
public class RouteBuildRefusedException extends ConfigApiException { // ponytail: the refusal IS the error path

    RouteBuildRefusedException(int httpStatus, String answer, String detail) {
        super(httpStatus, ConfigApiException.body("error", answer), detail);
    }

    /** RED stub (row 278 C2): compiles only; GREEN exposes the client-safe text here. */
    public String answer() {
        return null;
    }

    /** 400 - the row's endpoint URI scheme is not in allowedSchemes; the answer names the scheme only. */
    static RouteBuildRefusedException scheme(String scheme, String detail) {
        return new RouteBuildRefusedException(400,
                "the publish was refused: endpoint URI scheme '" + RefusalText.scheme(scheme)
                        + "' is not in allowedSchemes", detail);
    }

    /** 400 - no route template serves the row's target type; the answer names the type only. */
    static RouteBuildRefusedException targetType(String targetType, String detail) {
        return new RouteBuildRefusedException(400,
                "the publish was refused: no route template for target type '" + RefusalText.targetType(targetType)
                        + "'", detail);
    }
}
