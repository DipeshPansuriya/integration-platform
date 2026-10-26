package io.integrationplatform.routing.templates;

/**
 * The refusal of a route row (E9-F1-S2 @negative @security, T-120 machinery, E9-C6): a route whose configured
 * endpoint URI scheme is outside {@code allowedSchemes}, or whose target type has no template, is never built and
 * the publish fails with a clear error - configuration cannot reach an unapproved component (ADR-010 section 3).
 *
 * <p>Row 278 C2 (ADR-017 B2): routing's own type, with no HTTP status and nothing from config. {@link #answer()} is
 * the client-safe text; at publish, {@link InterfacePublishedRouteListener} translates the refusal into config's
 * {@code InterfacePublishRefused} with that answer as its reason, and config owns the 400.
 * Row 278 B22 (Kavita M1/L1): both {@link #answer()} and {@link #getMessage()} carry only {@link RefusalText}-bounded
 * scheme or type text plus the route and endpoint ids - never the endpoint URI, its userinfo, a query token or CR/LF.
 * {@link #getMessage()} is the log detail, including the stage 2 WARN line ({@code RouteInstanceSendDispatch}); the stage 2
 * dead-letter itself records no reason text.
 */
public class RouteBuildRefusedException extends RuntimeException {

    private final String answer;

    private RouteBuildRefusedException(String answer, String detail) {
        super(detail);
        this.answer = answer;
    }

    /** The client-safe text of the refusal: names the refused scheme or type only, never the endpoint URI. */
    public String answer() {
        return answer;
    }

    /** The row's endpoint URI scheme is not in allowedSchemes; the answer names the scheme only. */
    static RouteBuildRefusedException scheme(String scheme, String detail) {
        return new RouteBuildRefusedException("the publish was refused: endpoint URI scheme '"
                + RefusalText.scheme(scheme) + "' is not in allowedSchemes", detail);
    }

    /** No route template serves the row's target type; the answer names the type only. */
    static RouteBuildRefusedException targetType(String targetType, String detail) {
        return new RouteBuildRefusedException("the publish was refused: no route template for target type '"
                + RefusalText.targetType(targetType) + "'", detail);
    }

    /** The route id is already in the running Camel context without a held instance; the detail names the id. */
    static RouteBuildRefusedException routeIdClash(String detail) {
        return new RouteBuildRefusedException("the route instance is already in the running context", detail);
    }
}
