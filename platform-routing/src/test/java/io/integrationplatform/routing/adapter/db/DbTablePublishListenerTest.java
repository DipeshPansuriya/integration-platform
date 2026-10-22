package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.flow.api.InterfacePublishRefused;
import io.integrationplatform.config.flow.api.InterfacePublished;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** E16 F3 (design note task 10, ADR-017): a publish whose DB table is not approved is refused with a clear reason. */
class DbTablePublishListenerTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID iface = UUID.randomUUID();

    private InterfacePublished published() {
        return new InterfacePublished(tenant, iface, 1);
    }

    @Test
    @DisplayName("every DB table approved: the publish goes through")
    void approvedTablesPublish() {
        new DbTablePublishListener(id -> List.of(), id -> List.of()).onInterfacePublished(published());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("an unapproved table refuses the publish through the ADR-017 contract, naming system.table only")
    void anUnapprovedTableRefusesThePublish() {
        DbTablePublishListener listener = new DbTablePublishListener(id -> {
            assertThat(id).isEqualTo(iface);
            return List.of("orders-db.users");
        }, id -> List.of());

        assertThatThrownBy(() -> listener.onInterfacePublished(published()))
                .isInstanceOfSatisfying(InterfacePublishRefused.class,
                        e -> assertThat(e.reason()).contains("orders-db.users").contains("not approved"));
    }

    @Test
    @Tag("negative")
    @DisplayName("a failed lookup fails the publish (rolled back) - it is never read as approved")
    void aFailedLookupFailsThePublish() {
        DbTablePublishListener listener = new DbTablePublishListener(id -> {
            throw new IllegalStateException("db down");
        }, id -> List.of());

        assertThatThrownBy(() -> listener.onInterfacePublished(published())).isInstanceOf(IllegalStateException.class);
    }
}
