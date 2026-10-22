package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.file.FileTargetContent;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** E16 content wiring: the file body is the transformed payload; the name is the target's pattern or the delivery id. */
class TransformedFileContentTest {

    private static final Stage2Send SEND =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);
    private static final byte[] OUTPUT = "{\"order\":\"o-1\"}".getBytes(StandardCharsets.UTF_8);

    private static FileTargetContent content(String contentType, byte[] bytes, String settingsJson) {
        ApiTargetBody body = send -> bytes == null ? Optional.empty()
                : Optional.of(new ApiTargetBody.ApiWireBody(contentType, bytes));
        Function<UUID, Optional<String>> settings = id -> Optional.ofNullable(settingsJson);
        return TransformedFileContent.of(body, settings);
    }

    @Test
    void withoutAPatternTheNameIsTheDeliveryIdAndTheBodyIsTheTransformedBytes() {
        FileTargetContent.Content c = content("application/json", OUTPUT, "{\"rootPath\":\"/out\"}")
                .contentFor(SEND).orElseThrow();

        assertThat(c.fileName()).isEqualTo(SEND.deliveryId() + ".json");
        assertThat(c.body()).isEqualTo(OUTPUT);
    }

    @Test
    void aCsvPayloadGetsTheCsvExtension() {
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        FileTargetContent.Content c = content("text/csv; charset=utf-8", csv, "{\"rootPath\":\"/out\"}")
                .contentFor(SEND).orElseThrow();

        assertThat(c.fileName()).isEqualTo(SEND.deliveryId() + ".csv");
    }

    @Test
    void theTargetsFileNamePatternFillsDeliveryIdAndExtension() {
        FileTargetContent.Content c = content("application/json", OUTPUT,
                "{\"rootPath\":\"/out\",\"fileNamePattern\":\"orders-{deliveryId}.{ext}\"}")
                .contentFor(SEND).orElseThrow();

        assertThat(c.fileName()).isEqualTo("orders-" + SEND.deliveryId() + ".json");
    }

    @Test
    void aBlankOrMissingOrUnreadableSettingFallsBackToTheDefaultName() {
        for (String settings : new String[] {null, "{\"rootPath\":\"/out\",\"fileNamePattern\":\"  \"}", "not json"}) {
            assertThat(content("application/json", OUTPUT, settings).contentFor(SEND).orElseThrow().fileName())
                    .isEqualTo(SEND.deliveryId() + ".json");
        }
    }

    @Test
    void noTransformResultIsNoContent() {
        assertThat(content("application/json", null, "{\"rootPath\":\"/out\"}").contentFor(SEND)).isEmpty();
    }
}
