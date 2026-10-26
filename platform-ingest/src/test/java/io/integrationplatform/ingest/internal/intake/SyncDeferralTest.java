package io.integrationplatform.ingest.internal.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** T-129 part 1: a SYNC single-record intake writes its outbox row deferred by 10 s + 5 s (architecture.md 4.2). */
class SyncDeferralTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void syncSingleRecordIsDeferredFifteenSeconds() {
        assertThat(IntakeKeys.deferredUntil("SYNC", true, NOW)).isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void asyncOrBulkIsNotDeferred() {
        assertThat(IntakeKeys.deferredUntil("ASYNC", true, NOW)).isNull();
        assertThat(IntakeKeys.deferredUntil("SYNC", false, NOW)).isNull();
        assertThat(IntakeKeys.deferredUntil(null, true, NOW)).isNull();
    }
}
