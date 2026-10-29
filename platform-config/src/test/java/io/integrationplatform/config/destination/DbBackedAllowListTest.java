package io.integrationplatform.config.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbBackedAllowListTest {

    private static final String SELECT = "SELECT pattern FROM operator_allowed_host ORDER BY pattern";

    @Test
    @DisplayName("an unreadable operator_allowed_host does not stop startup: fail closed, configured hosts still allowed")
    void unreadableTableFailsClosedWithoutStoppingStartup() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(SELECT).query(String.class).list())
                .thenThrow(new DataAccessResourceFailureException("permission denied for table operator_allowed_host"));

        DbBackedAllowList list = new DbBackedAllowList(AllowList.of(List.of("ok.example.com")), jdbc, Clock.systemUTC());

        assertThat(list.allows("ok.example.com")).isTrue();
        assertThat(list.allows("evil.example")).isFalse();
    }

    @Test
    @DisplayName("a successful refresh is not re-run within 30s")
    void successfulRefreshIsNotRerunWithin30s() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(SELECT).query(String.class).list())
                .thenReturn(List.of("db.example.com"));

        DbBackedAllowList list = new DbBackedAllowList(AllowList.of(List.of()), jdbc, Clock.systemUTC());

        assertThat(list.allows("db.example.com")).isTrue();
        assertThat(list.allows("db.example.com")).isTrue();

        verify(jdbc, times(2)).sql(SELECT);
    }

    @Test
    @DisplayName("a failed load is retried once the 30s window has elapsed")
    void failedLoadIsRetriedAfter30s() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(SELECT).query(String.class).list())
                .thenThrow(new DataAccessResourceFailureException("permission denied"))
                .thenReturn(List.of("db.example.com"));

        MutableClock clock = new MutableClock();
        DbBackedAllowList list = new DbBackedAllowList(AllowList.of(List.of()), jdbc, clock);

        assertThat(list.allows("db.example.com")).isFalse();
        clock.advanceMillis(31_000);
        assertThat(list.allows("db.example.com")).isTrue();
    }

    @Test
    @DisplayName("too-broad host patterns are rejected up front")
    void addRejectsTooBroadPatterns() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        DbBackedAllowList list = new DbBackedAllowList(AllowList.of(List.of()), jdbc, Clock.systemUTC());

        assertThatThrownBy(() -> list.add("*.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern too broad");
        assertThatThrownBy(() -> list.add("*." ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern too broad");
        assertThatThrownBy(() -> list.add("*.single"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern too broad");

        verify(jdbc, never()).sql(anyString());
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(0);

        void advanceMillis(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
