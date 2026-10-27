package io.integrationplatform.routing.credential;

/**
 * Thread-scoped holder that hands {@link OpenedCredentials} to the one target call that needs them
 * ({@code ApiTargetCall}). Row 540: it is bound ONLY by {@code LayerOneRouteBuilder#oneTry}, on the thread that
 * performs the call, from the exchange property {@code TARGET_CALL_SECRETS} that carries the values from the dispatch
 * (so a thread hop earlier in the route cannot lose them), and cleared in a finally right after the call.
 *
 * <p>The credentials themselves are wiped by
 * {@code CredentialedDelivery}'s try-with-resources regardless of this holder's state.
 */
public final class TargetCallCredentialHolder {

    private static final ThreadLocal<OpenedCredentials> HOLDER = new ThreadLocal<>();

    private TargetCallCredentialHolder() {
    }

    public static void set(OpenedCredentials credentials) {
        HOLDER.set(credentials);
    }

    public static OpenedCredentials get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
