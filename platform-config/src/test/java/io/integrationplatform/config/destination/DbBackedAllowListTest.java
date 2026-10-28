package io.integrationplatform.config.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbBackedAllowListTest {

    @Test
    @DisplayName("an unreadable operator_allowed_host does not stop startup: fail closed, configured hosts still allowed")
    void unreadableTableFailsClosedWithoutStoppingStartup() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("SELECT pattern FROM operator_allowed_host ORDER BY pattern").query(String.class).list())
                .thenThrow(new DataAccessResourceFailureException("permission denied for table operator_allowed_host"));

        DbBackedAllowList list = new DbBackedAllowList(AllowList.of(List.of("ok.example.com")), jdbc);

        assertThat(list.allows("ok.example.com")).isTrue();
        assertThat(list.allows("evil.example")).isFalse();
    }
}
