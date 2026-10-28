package io.integrationplatform.app.audit;

import java.util.Locale;

/**
 * Row 544 (D-5): how the audit relay moves outbox rows into the ledger. {@link #DEFINER} (default) calls
 * {@code public.audit_relay_move}; {@link #LEGACY} is the previous single UPDATE+INSERT statement and works only after
 * the rollback SQL of V2_4_1490 re-granted the two privileges (the flag alone is not a rollback). Property
 * {@code ipaas.audit.relay.mode} / env {@code IPAAS_AUDIT_RELAY_MODE}; any other value fails the start, never a silent
 * fallback.
 */
enum RelayMode {
    DEFINER,
    LEGACY;

    static RelayMode parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFINER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ipaas.audit.relay.mode must be DEFINER or LEGACY, got '" + value + "'", e);
        }
    }
}
