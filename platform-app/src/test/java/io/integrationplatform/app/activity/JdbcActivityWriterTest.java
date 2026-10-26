package io.integrationplatform.app.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.integrationplatform.audit.api.ActivityEntry;
import io.integrationplatform.audit.api.ActivityWriteApi;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E12 write side without Docker: the writer fails closed - with no tenant bound it refuses before any SQL runs. The
 * SQL, the trace round trip and tenant isolation against the real ledger are {@code ActivityWriterContainerTest}'s.
 */
class JdbcActivityWriterTest {

    private static final ActivityEntry ENTRY = new ActivityEntry("admin-a", "CONFIG_CHANGED", List.of("flow:1"),
            null, null, Instant.parse("2026-10-21T08:00:00Z"), "trace-1");

    @Test
    void withNoTenantBoundTheWriterRefusesBeforeAnySqlRuns() {
        JdbcClient jdbc = mock(JdbcClient.class);
        TenantContext unbound = () -> Optional.empty();
        ActivityWriteApi writer = new JdbcActivityWriter(jdbc, unbound);

        assertThatThrownBy(() -> writer.record(ENTRY)).isInstanceOf(NoTenantBoundException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void aNullEntryIsRefused() {
        ActivityWriteApi writer = new JdbcActivityWriter(mock(JdbcClient.class), () -> Optional.empty());

        assertThatThrownBy(() -> writer.record(null)).isInstanceOf(NullPointerException.class);
        assertThat(writer).isNotNull();
    }
}
