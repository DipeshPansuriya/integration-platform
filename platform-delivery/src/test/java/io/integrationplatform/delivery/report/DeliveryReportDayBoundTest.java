package io.integrationplatform.delivery.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.delivery.port.DeliveryDailyAggregates.DailyCounts;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * T187 (Varun review of T185): the report day is bounded - a future day or a day older than the configured window is a
 * 400 - and the on-demand count over the delivery rows (which scans the whole UTC-day partition) runs only for the two
 * days the nightly roll-up may not have stored yet (today and yesterday); a stored non-empty roll-up always wins.
 */
class DeliveryReportDayBoundTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC);
    private static final int MAX_AGE_DAYS = 31;
    private static final DailyCounts ROW = new DailyCounts(UUID.randomUUID(), UUID.randomUUID(), 1, 0, 0, 0, 0, 0, 0);

    private final Spy spy = new Spy();
    private final DeliveryReportController controller =
            new DeliveryReportController(spy, new NoopTransactions(), CLOCK, MAX_AGE_DAYS);

    @Test
    @DisplayName("A future day is a 400 and nothing is read")
    void futureDayIsRejected() {
        assertThatThrownBy(() -> controller.report(TODAY.plusDays(1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(spy.calls).isEmpty();
    }

    @Test
    @DisplayName("A day older than the window is a 400 and nothing is read; the oldest allowed day is served")
    void tooOldDayIsRejected() {
        assertThatThrownBy(() -> controller.report(TODAY.minusDays(MAX_AGE_DAYS + 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(spy.calls).isEmpty();

        assertThat(controller.report(TODAY.minusDays(MAX_AGE_DAYS)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Today and yesterday with no stored roll-up are counted from the delivery rows")
    void todayAndYesterdayFallBackToDirect() {
        spy.direct = List.of(ROW);

        assertThat(controller.report(TODAY).getBody()).containsExactly(ROW);
        assertThat(controller.report(TODAY.minusDays(1)).getBody()).containsExactly(ROW);
        assertThat(spy.calls).containsExactly("aggregated", "direct", "aggregated", "direct");
    }

    @Test
    @DisplayName("An older day with no stored roll-up is empty - the whole-day partition is not scanned for it")
    void olderDayDoesNotScanThePartition() {
        spy.direct = List.of(ROW);

        assertThat(controller.report(TODAY.minusDays(2)).getBody()).isEmpty();
        assertThat(spy.calls).containsExactly("aggregated");
    }

    @Test
    @DisplayName("A stored non-empty roll-up wins and the delivery rows are not scanned")
    void storedRollupWins() {
        spy.stored = List.of(ROW);
        spy.direct = List.of();

        assertThat(controller.report(TODAY).getBody()).containsExactly(ROW);
        assertThat(spy.calls).containsExactly("aggregated");
    }

    private static final class Spy implements DeliveryDailyAggregates {
        final List<String> calls = new ArrayList<>();
        List<DailyCounts> stored = List.of();
        List<DailyCounts> direct = List.of();

        @Override
        public int refresh(LocalDate day) {
            throw new AssertionError("the report never writes");
        }

        @Override
        public List<DailyCounts> aggregated(LocalDate day) {
            calls.add("aggregated");
            return stored;
        }

        @Override
        public List<DailyCounts> direct(LocalDate day) {
            calls.add("direct");
            return direct;
        }
    }

    private static final class NoopTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
