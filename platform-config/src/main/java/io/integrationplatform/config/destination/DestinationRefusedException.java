package io.integrationplatform.config.destination;

import java.util.Objects;

/** The guard refused a destination before anything was dialled; {@link #detail()} names the range, when one applies. */
public final class DestinationRefusedException extends Exception {

    private final Refusal refusal;
    private final String detail;

    /**
     * R10 (CWE-209): the message is the SAME sentence for every {@link Refusal#REFUSED_ADDRESS} - a refused range and
     * a platform host read identically, so a caller cannot map which addresses are the platform's own. The range's
     * name (for the audit log only) rides in {@link #detail()}.
     */
    public DestinationRefusedException(Refusal refusal, String detail) {
        super("destination refused: " + refusal, null, false, false);
        this.refusal = Objects.requireNonNull(refusal, "refusal");
        this.detail = detail;
    }

    public Refusal refusal() {
        return refusal;
    }

    /** The refused range's name for {@link Refusal#REFUSED_ADDRESS} (never the address itself), else {@code null}. */
    public String detail() {
        return detail;
    }
}
