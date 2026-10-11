package io.integrationplatform.config.destination;

import java.util.Objects;

/** The guard refused a destination before anything was dialled; {@link #detail()} names the range, when one applies. */
public final class DestinationRefusedException extends Exception {

    private final Refusal refusal;
    private final String detail;

    public DestinationRefusedException(Refusal refusal, String detail) {
        super("destination refused: " + refusal + (detail == null ? "" : " (" + detail + ")"), null, false, false);
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
