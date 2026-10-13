package io.integrationplatform.routing.templates;

/**
 * The publish-side refusal of an unapproved endpoint scheme (E9-F1-S2 @negative @security, T-120 machinery,
 * E9-C6): a route whose configured endpoint URI scheme is outside {@code allowedSchemes} is never built and the
 * publish fails with a clear error - configuration cannot reach an unapproved component (ADR-010 section 3).
 */
public class RouteBuildRefusedException extends RuntimeException { // ponytail: the refusal IS the error path

    public RouteBuildRefusedException(String message) {
        super(message);
    }
}