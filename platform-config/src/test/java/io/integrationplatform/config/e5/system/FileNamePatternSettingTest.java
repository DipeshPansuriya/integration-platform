package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** E16 content wiring: the FILE system's optional fileNamePattern - a plain name with only {deliveryId}/{ext}. */
class FileNamePatternSettingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static java.util.List<String> errors(String settings) {
        return SystemSettings.validate(JSON.readTree("\"FILE\""), JSON.readTree(settings));
    }

    @Test
    void aPlainPatternIsAccepted() {
        assertThat(errors("{\"rootPath\":\"/o\",\"fileNamePattern\":\"orders-{deliveryId}.{ext}\"}")).isEmpty();
        assertThat(errors("{\"rootPath\":\"/o\"}")).isEmpty();
    }

    @Test
    void aPatternThatCouldLeaveTheFolderOrTakeAnotherPlaceholderIsRefused() {
        for (String bad : new String[] {"../x-{deliveryId}", "a/b-{deliveryId}", "a\\b", "c:x", "..", "",
                "{other}.txt", "x".repeat(130)}) {
            assertThat(errors(JSON.writeValueAsString(java.util.Map.of("rootPath", "/o", "fileNamePattern", bad))))
                    .as("pattern '%s'", bad).isNotEmpty();
        }
    }

    @Test
    void aPatternWithoutDeliveryIdIsRefusedBecauseTheSecondDeliveryWouldDeadLetter() {
        for (String bad : new String[] {"orders.csv", "orders-{ext}", "fixed-name.{ext}"}) {
            assertThat(errors(JSON.writeValueAsString(java.util.Map.of("rootPath", "/o", "fileNamePattern", bad))))
                    .as("pattern '%s'", bad).singleElement().asString().contains("{deliveryId}");
        }
    }
}
