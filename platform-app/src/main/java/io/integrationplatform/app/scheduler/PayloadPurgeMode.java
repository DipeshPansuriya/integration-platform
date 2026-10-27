package io.integrationplatform.app.scheduler;

import java.util.Locale;

/**
 * How PAYLOAD_PURGE behaves (E14 T-142). The purge deletes data, so the shipped default is {@link #OFF}: a deployment
 * must opt in, first to {@link #DRY_RUN} (selection and guard run in the database, nothing is dropped) and then to
 * {@link #ENFORCE}. Set with {@code ipaas.retention.payload-purge.mode}.
 */
public enum PayloadPurgeMode {
    OFF, DRY_RUN, ENFORCE;

    /** Reads the configured text; anything missing or not exactly a mode name is OFF - never delete on a typo. */
    public static PayloadPurgeMode parse(String text) {
        if (text == null) {
            return OFF;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OFF;
        }
    }
}
