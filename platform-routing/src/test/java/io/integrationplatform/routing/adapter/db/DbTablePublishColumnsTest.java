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

/** E16 F3 (design note task 9, ADR-017): a publish whose mapped columns are not in the approved contract is refused. */
class DbTablePublishColumnsTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID iface = UUID.randomUUID();
    private final MissingContractColumns allPresent = id -> List.of();
    private final UnapprovedTables allApproved = id -> List.of();

    private InterfacePublished published() {
        return new InterfacePublished(tenant, iface, 1);
    }

    @Test
    @DisplayName("every mapped column in the approved contract: the publish goes through")
    void mappedColumnsInTheContractPublish() {
        new DbTablePublishListener(allApproved, allPresent).onInterfacePublished(published());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("a mapped column missing from the approved contract refuses the publish, naming column")
    void aMissingColumnRefusesThePublish() {
        DbTablePublishListener listener = new DbTablePublishListener(allApproved,
                id -> List.of("orders-db.orders_in.tracking_no"));

        assertThatThrownBy(() -> listener.onInterfacePublished(published()))
                .isInstanceOfSatisfying(InterfacePublishRefused.class,
                        e -> assertThat(e.reason()).contains("orders-db.orders_in.tracking_no")
                                .contains("approved contract"));
    }

    @Test
    @Tag("negative")
    @DisplayName("the unapproved-table refusal still fires first when both are off")
    void theTableRefusalStillFiresFirst() {
        DbTablePublishListener listener = new DbTablePublishListener(id -> List.of("orders-db.orders_in", "orders-db.users"),
                id -> List.of("orders-db.orders_in.tracking_no"));

        assertThatThrownBy(() -> listener.onInterfacePublished(published()))
                .isInstanceOfSatisfying(InterfacePublishRefused.class,
                        e -> assertThat(e.reason()).contains("not approved").doesNotContain("tracking_no"));
    }

    @Test
    @Tag("negative")
    @DisplayName("a failed column lookup fails the publish (rolled back) - never read as all-present")
    void aFailedColumnLookupFailsThePublish() {
        DbTablePublishListener listener = new DbTablePublishListener(allApproved, id -> {
            throw new IllegalStateException("db down");
        });

        assertThatThrownBy(() -> listener.onInterfacePublished(published())).isInstanceOf(IllegalStateException.class);
    }
}