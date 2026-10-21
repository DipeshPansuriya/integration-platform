package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** E16 T-150: the production File outbound - stored FILE settings plus the mapped name and body, no Docker. */
class FileTargetOutboundTest {

    static final Stage2Send SEND = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);
    static final FileTargetContent CONTENT =
            send -> Optional.of(new FileTargetContent.Content("o-1.csv", new byte[] {1}));

    @Test
    void storedSettingsAndMappedContentBecomeTheFileMessage() {
        FileMessage message = new FileTargetOutbound(id -> Optional.of("{\"rootPath\":\"/data/out\"}"), CONTENT)
                .resolve(SEND).orElseThrow();

        assertThat(message.settings().directory()).isEqualTo("/data/out");
        assertThat(message.fileName()).isEqualTo("o-1.csv");
        assertThat(message.body()).containsExactly(1);
    }

    @Test
    void noFileSystemOrNoContentResolvesEmpty() {
        assertThat(new FileTargetOutbound(id -> Optional.empty(), CONTENT).resolve(SEND)).isEmpty();
        assertThat(new FileTargetOutbound(id -> Optional.of("{\"rootPath\":\"/d\"}"), send -> Optional.empty())
                .resolve(SEND)).isEmpty();
    }

    @Test
    void aBrokenSettingsRowIsPermanent() {
        assertThatThrownBy(() -> new FileTargetOutbound(id -> Optional.of("{}"), CONTENT).resolve(SEND))
                .isInstanceOfSatisfying(TargetFaultException.class,
                        fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
    }
}
