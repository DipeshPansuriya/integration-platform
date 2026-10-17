package io.integrationplatform.config.flow.api;

/**
 * A listener of {@link InterfacePublished} said no to the publish (row 278 C2, ADR-017 Decision 1). The publish
 * event is config's public contract, so config also owns how a listener refuses it:
 *
 * <ul>
 *   <li>A {@code BEFORE_COMMIT} listener of {@link InterfacePublished} refuses the publish by throwing this.</li>
 *   <li>The publish transaction rolls back; the configuration API answers 400 with {@code {"error": reason}}.</li>
 *   <li>{@link #reason()} is shown to a tenant admin, so the thrower sanitises it: no URI, no userinfo, no
 *       credential, bounded length. The cause keeps the thrower's own detail for logs and is never answered.</li>
 * </ul>
 */
public class InterfacePublishRefused extends RuntimeException {

    private final String reason;

    public InterfacePublishRefused(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** The client-safe reason, already sanitised by the thrower. */
    public String reason() {
        return reason;
    }
}
