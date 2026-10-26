package io.integrationplatform.config.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T178c-1: counting the stored FILE destinations whose pattern lacks {deliveryId} (a count, never the values). */
class FileSystemsWithoutDeliveryIdTest {

    private final JdbcSystemRepository systems = mock(JdbcSystemRepository.class);
    private final SystemAdminService service = new SystemAdminService(systems, mock(TestConnectionService.class),
            new DestinationGuard(AllowList.of(List.of("*.partner.example")), HostResolver.system()), FileRoots.none(),
            () -> Optional.of(UUID.randomUUID()), false, event -> { });

    private void system(String code, String type, String settings) {
        when(systems.findSystemByCode(code)).thenReturn(Optional.of(new SystemRow(code + "-id", code, code, "TARGET")));
        when(systems.findSystem(code + "-id"))
                .thenReturn(Optional.of(new SystemDetail(code + "-id", code, code, "TARGET", type, settings)));
    }

    @Test
    void onlyStoredFileDestinationsWithAFixedNameAreCounted() {
        system("fixed", "FILE", "{\"rootPath\":\"/o\",\"fileNamePattern\":\"orders.csv\"}");
        system("safe", "FILE", "{\"rootPath\":\"/o\",\"fileNamePattern\":\"o-{deliveryId}.{ext}\"}");
        system("default", "FILE", "{\"rootPath\":\"/o\"}");
        system("api", "API", "{\"baseUrl\":\"https://a.partner.example\"}");
        when(systems.findSystemByCode("unregistered")).thenReturn(Optional.empty());

        assertThat(service.countFileDestinationsWithoutDeliveryId(List.of("fixed", "safe", "default", "api", "unregistered")))
                .isEqualTo(1);
        assertThat(service.countFileDestinationsWithoutDeliveryId(List.of("safe", "default", "api"))).isZero();
        assertThat(service.countFileDestinationsWithoutDeliveryId(List.of())).isZero();
    }
}
