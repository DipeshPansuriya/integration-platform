package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Row 570 (Karan re-review, Lows 2 and 3): the guard's no-database branches. The SQL branches are the startup container test. */
class AuditLedgerLockdownGuardTest {

    private static AuditLedgerLockdownGuard guard(RelayMode mode, Map<String, Object> beans) {
        return new AuditLedgerLockdownGuard(new StaticListableBeanFactory(beans).getBeanProvider(DataSource.class), mode);
    }

    @Test
    void legacyModeLogsThatTheLockdownIsNotEnforced() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLedgerLockdownGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> guard(RelayMode.LEGACY, Map.of()).afterSingletonsInstantiated())
                    .doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("audit ledger lockdown not enforced");
        });
    }

    @Test
    void anAmbiguousDataSourceFailsTheStartInDefinerModeInsteadOfSkippingTheCheck() {
        Map<String, Object> two = Map.of("a", mock(DataSource.class), "b", mock(DataSource.class));

        assertThatThrownBy(() -> guard(RelayMode.DEFINER, two).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("more than one DataSource");
    }

    @Test
    void noDataSourceAtAllStillSkipsTheCheck() {
        assertThatCode(() -> guard(RelayMode.DEFINER, Map.of()).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }
}
