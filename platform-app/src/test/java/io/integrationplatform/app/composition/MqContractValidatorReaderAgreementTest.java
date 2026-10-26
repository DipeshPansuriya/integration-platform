package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import io.integrationplatform.routing.adapter.mq.CsvContractSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * T176-3: config's save-time validator ({@link SystemSettings}) and routing's send-time reader
 * ({@link CsvContractSettings}) cannot see each other, so only the composition root can pin that they agree. The
 * rule: a contract the validator saves is one the reader can load (the validator may be stricter, never looser).
 * Each case below states both verdicts, so a drift on either side fails here, not on the first real send.
 */
class MqContractValidatorReaderAgreementTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GOOD = "{\"columns\":[{\"name\":\"id\",\"type\":\"TEXT\"},{\"name\":\"qty\",\"type\":\"number\"}],"
            + "\"delimiter\":\";\",\"quote\":\"\\\"\",\"quoteAll\":false,\"encoding\":\"UTF-8\",\"lineEnding\":\"CRLF\","
            + "\"header\":true}";

    private enum Verdict { ACCEPT, REJECT }

    private static Verdict validator(String contract) {
        List<String> errors = SystemSettings.validate(JSON.getNodeFactory().stringNode("MQ"), JSON.readTree(
                "{\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"Q1\",\"contract\":" + contract + "}"));
        return errors.isEmpty() ? Verdict.ACCEPT : Verdict.REJECT;
    }

    private static Verdict reader(String contract) {
        try {
            CsvContractSettings.fromJson(contract);
            return Verdict.ACCEPT;
        } catch (RuntimeException refused) {
            return Verdict.REJECT;
        }
    }

    /** contract -> {validator, reader}; the first block is where both must agree, the second where the validator is stricter on purpose. */
    private static Map<String, Verdict[]> cases() {
        Map<String, Verdict[]> cases = new LinkedHashMap<>();
        Verdict[] both = {Verdict.ACCEPT, Verdict.ACCEPT};
        Verdict[] neither = {Verdict.REJECT, Verdict.REJECT};
        cases.put(GOOD, both);
        cases.put(GOOD.replace("\"TEXT\"", "\"text\""), both);
        cases.put(GOOD.replace("CRLF", "LF"), both);
        cases.put(GOOD.replace("CRLF", "lf"), both);
        cases.put(GOOD.replace("UTF-8", "ISO-8859-1"), both);
        cases.put(GOOD.replace("\"quoteAll\":false", "\"quoteAll\":true"), both);
        cases.put(GOOD.replace("UTF-8", "NOPE-9"), neither);
        cases.put(GOOD.replace("CRLF", "CR"), neither);
        cases.put(GOOD.replace("\";\"", "\";;\""), neither);
        cases.put(GOOD.replace("\"TEXT\"", "\"DATE\""), neither);
        cases.put(GOOD.replace("\"header\":true", "\"header\":\"yes\""), neither);
        cases.put(GOOD.replace("\"quoteAll\":false,", ""), neither);
        cases.put(GOOD.replace("\"columns\"", "\"cols\""), neither);
        cases.put("{\"columns\":\"id\"}", neither);
        // the validator is stricter on purpose: free-text column names and a line-break delimiter load, but are never saved
        cases.put(GOOD.replace("\"id\"", "\"id; drop\""), new Verdict[] {Verdict.REJECT, Verdict.ACCEPT});
        cases.put(GOOD.replace("\";\"", "\"\\n\""), new Verdict[] {Verdict.REJECT, Verdict.ACCEPT});
        // T176-2: a decode-only encoding loads in the reader but cannot encode a send, so it is never saved
        if (java.nio.charset.Charset.isSupported("ISO-2022-CN") && !java.nio.charset.Charset.forName("ISO-2022-CN").canEncode()) {
            cases.put(GOOD.replace("UTF-8", "ISO-2022-CN"), new Verdict[] {Verdict.REJECT, Verdict.ACCEPT});
        }
        return cases;
    }

    @Test
    @DisplayName("The validator and the reader give the stated verdict on the same contract")
    void bothSidesGiveTheStatedVerdict() {
        cases().forEach((contract, expected) -> {
            assertThat(validator(contract)).as("validator on %s", contract).isEqualTo(expected[0]);
            assertThat(reader(contract)).as("reader on %s", contract).isEqualTo(expected[1]);
        });
    }

    @Test
    @DisplayName("A contract the validator saves is never one the reader refuses")
    void aSavedContractAlwaysLoads() {
        cases().keySet().stream().filter(contract -> validator(contract) == Verdict.ACCEPT)
                .forEach(contract -> assertThat(reader(contract)).as("reader on saved %s", contract)
                        .isEqualTo(Verdict.ACCEPT));
    }
}
