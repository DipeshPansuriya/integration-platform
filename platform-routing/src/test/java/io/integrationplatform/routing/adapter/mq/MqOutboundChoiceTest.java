package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * E18 finding / E15 F15 "API and MQ-CSV": the MQ target type sends the CSV bytes when the MQ system's stored
 * settings carry a {@code contract} section (ADR-011: the MQ target is a CSV file on the queue; the contract rides
 * in the registry JSON, {@link JdbcMqCsvTargets}); settings without one keep the plain transformed-body outbound.
 */
class MqOutboundChoiceTest {

    private static final String PLAIN = "{\"host\":\"h\",\"port\":1414,\"queue\":\"q\"}";
    private static final String WITH_CONTRACT = "{\"host\":\"h\",\"port\":1414,\"queue\":\"q\",\"contract\":"
            + "{\"columns\":[{\"name\":\"awb\",\"type\":\"TEXT\"}],\"delimiter\":\";\",\"quote\":\"\\\"\"}}";

    private final Stage2Send send =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);
    private final MqMessage plainMessage = new MqMessage(new MqSettings("h", 1414, "q"), null, null, new byte[] {'p'});
    private final MqMessage csvMessage = new MqMessage(new MqSettings("h", 1414, "q"), null, null, new byte[] {'c'});
    private final MqOutbound plain = s -> Optional.of(plainMessage);
    private final MqOutbound csv = s -> Optional.of(csvMessage);

    @Test
    void settingsWithAContractSectionUseTheCsvOutbound() {
        MqOutbound chosen = MqTargetCallConfiguration.chooseOutbound(id -> Optional.of(WITH_CONTRACT), plain, csv);
        assertThat(chosen.resolve(send)).containsSame(csvMessage);
    }

    @Test
    void settingsWithoutAContractKeepThePlainOutbound() {
        MqOutbound chosen = MqTargetCallConfiguration.chooseOutbound(id -> Optional.of(PLAIN), plain, csv);
        assertThat(chosen.resolve(send)).containsSame(plainMessage);
    }

    @Test
    void noSettingsRowKeepsThePlainOutboundWhichRefusesIt() {
        MqOutbound chosen = MqTargetCallConfiguration.chooseOutbound(id -> Optional.empty(), plain, csv);
        assertThat(chosen.resolve(send)).containsSame(plainMessage);
    }

    @Test
    void withoutACsvOutboundEverythingStaysPlain() {
        MqOutbound chosen = MqTargetCallConfiguration.chooseOutbound(id -> Optional.of(WITH_CONTRACT), plain, null);
        assertThat(chosen.resolve(send)).containsSame(plainMessage);
    }

    @Test
    void unreadableSettingsFallToThePlainOutboundWhichRefusesThemPermanent() {
        MqOutbound chosen = MqTargetCallConfiguration.chooseOutbound(id -> Optional.of("{not json"), plain, csv);
        assertThat(chosen.resolve(send)).containsSame(plainMessage);
    }
}
