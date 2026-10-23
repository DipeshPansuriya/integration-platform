package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** T176: the MQ schema accepts the typed, bounded {@code contract} that JdbcMqCsvTargets reads. Not container-tagged. */
class MqCsvContractSettingsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD = "{\"columns\":[{\"name\":\"id\",\"type\":\"TEXT\"},{\"name\":\"qty\",\"type\":\"number\"}],"
            + "\"delimiter\":\";\",\"quote\":\"\\\"\",\"quoteAll\":false,\"encoding\":\"UTF-8\",\"lineEnding\":\"CRLF\","
            + "\"header\":true}";

    private static List<String> mq(String contractJson) {
        return SystemSettings.validate(JSON.getNodeFactory().stringNode("MQ"), JSON.readTree(
                "{\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"Q1\",\"contract\":" + contractJson + "}"));
    }

    @Test
    @DisplayName("An MQ-CSV target with a full contract is accepted; the contract stays optional")
    void aFullContractIsAccepted() {
        assertThat(mq(GOOD)).isEmpty();
        assertThat(SystemSettings.validate(JSON.getNodeFactory().stringNode("MQ"),
                JSON.readTree("{\"host\":\"h\",\"port\":1,\"queue\":\"Q\"}"))).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("A contract with more than 200 columns is refused (size limit)")
    void anOversizeContractIsRefused() {
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            columns.append(i == 0 ? "" : ",").append("{\"name\":\"c").append(i).append("\",\"type\":\"TEXT\"}");
        }
        assertThat(mq("{\"columns\":[" + columns + "]," + GOOD.substring(GOOD.indexOf("\"delimiter\""))))
                .singleElement().asString().startsWith("settings.contract.columns").contains("200");
    }

    @Test
    @Tag("negative")
    @DisplayName("Each contract field is typed: a free-text column name, bad type, multi-char delimiter, unknown encoding are refused")
    void badFieldsAreRefused() {
        assertThat(mq(GOOD.replace("\"id\"", "\"id; drop\""))).singleElement().asString()
                .contains("settings.contract.columns[0].name");
        assertThat(mq(GOOD.replace("\"TEXT\"", "\"DATE\""))).singleElement().asString()
                .contains("settings.contract.columns[0].type");
        assertThat(mq(GOOD.replace("\";\"", "\";;\""))).singleElement().asString().contains("settings.contract.delimiter");
        assertThat(mq(GOOD.replace("UTF-8", "NOPE-9"))).singleElement().asString().contains("settings.contract.encoding");
        assertThat(mq(GOOD.replace("CRLF", "CR"))).singleElement().asString().contains("settings.contract.lineEnding");
        assertThat(mq(GOOD.replace("\"header\":true", "\"header\":\"yes\""))).singleElement().asString()
                .contains("settings.contract.header");
    }

    @Test
    @Tag("negative")
    @DisplayName("T176-2: an encoding the JDK can only decode is refused at save time (the send needs an encoder)")
    void aDecodeOnlyEncodingIsRefused() {
        String decodeOnly = "ISO-2022-CN";
        // JDK fact this test depends on: the charset is known (isSupported) but has no encoder (canEncode false)
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.charset.Charset.isSupported(decodeOnly)
                && !java.nio.charset.Charset.forName(decodeOnly).canEncode(), "this JDK has no decode-only " + decodeOnly);

        assertThat(mq(GOOD.replace("UTF-8", decodeOnly))).singleElement().asString()
                .contains("settings.contract.encoding");
        assertThat(mq(GOOD.replace("UTF-8", "ISO-8859-1"))).isEmpty(); // an encodable one still passes
    }

    @Test
    @Tag("negative")
    @DisplayName("Delimiter equal to quote, a missing field, an unknown key, a non-object contract are refused")
    void structureIsEnforced() {
        assertThat(mq(GOOD.replace("\";\"", "\"\\\"\""))).singleElement().asString().contains("must differ");
        assertThat(mq(GOOD.replace("\"header\":true", "\"x\":1"))).hasSize(2); // header missing + x unknown
        assertThat(mq("\"csv\"")).singleElement().asString().isEqualTo("settings.contract must be a JSON object");
        assertThat(mq("{\"columns\":[]}")).anyMatch(e -> e.contains("settings.contract.columns"));
    }

    @Test
    @Tag("negative")
    @DisplayName("T189-6: two columns with the same name (case-insensitive) are refused with a clear error")
    void duplicateColumnNamesAreRefused() {
        assertThat(mq(GOOD.replace("\"qty\"", "\"id\"")))
                .singleElement().asString().contains("settings.contract.columns[1].name")
                .contains("duplicate").contains("columns[0]");
        assertThat(mq(GOOD.replace("\"qty\"", "\"ID\"")))
                .singleElement().asString().contains("settings.contract.columns[1].name").contains("duplicate");
    }
}
