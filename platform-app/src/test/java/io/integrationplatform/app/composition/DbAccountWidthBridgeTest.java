package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import io.integrationplatform.routing.adapter.db.DbAccountWidth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * B30: routing may not use config's application types, so the composition root hands config's approval the answer
 * of routing's own width port - same verdict, same fail-closed default when routing's DB slice is not composed.
 */
class DbAccountWidthBridgeTest {

    private static final UUID SYSTEM = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DbAccountWidth> providing(DbAccountWidth port) {
        ObjectProvider<DbAccountWidth> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return provider;
    }

    @Test
    void aWideAccountRefusalReachesConfigsApprovalUnchanged() {
        String refusal = "DB_ACCOUNT_TOO_WIDE: the account 'a' holds more than INSERT and SELECT";
        DbAccountWidthCheck check = new CompositionConfiguration()
                .dbAccountWidthCheck(providing((systemId, settings) -> Optional.of(refusal)));

        assertThat(check.refusalFor(SYSTEM, "{}")).contains(refusal);
    }

    @Test
    void aNarrowAccountIsNoRefusal() {
        DbAccountWidthCheck check = new CompositionConfiguration()
                .dbAccountWidthCheck(providing((systemId, settings) -> Optional.empty()));

        assertThat(check.refusalFor(SYSTEM, "{}")).isEmpty();
    }

    @Test
    void theSystemAndSettingsReachTheRoutingPortAsGiven() {
        DbAccountWidthCheck check = new CompositionConfiguration().dbAccountWidthCheck(providing(
                (systemId, settings) -> Optional.of(systemId + "|" + settings)));

        assertThat(check.refusalFor(SYSTEM, "{\"table\":\"t\"}")).contains(SYSTEM + "|{\"table\":\"t\"}");
    }

    @Test
    void withoutRoutingsDbSliceNoCheckIsComposedSoConfigsFailClosedDefaultApplies() {
        assertThat(new CompositionConfiguration().dbAccountWidthCheck(providing(null))).isNull();
    }
}
