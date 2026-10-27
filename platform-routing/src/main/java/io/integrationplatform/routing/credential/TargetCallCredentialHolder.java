package io.integrationplatform.routing.credential;

/**
 * Thread-scoped holder for passing {@link OpenedCredentials} from {@code RouteInstanceSendDispatch} through
 * the synchronous Camel {@code direct:} route to {@code ApiTargetCall}. The direct endpoint processes on the
 * caller's thread, so a ThreadLocal set before {@code producer.send()} is visible inside the target call.
 *
 * <p>Always clear in a finally block after the producer returns. The credentials themselves are wiped by
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
