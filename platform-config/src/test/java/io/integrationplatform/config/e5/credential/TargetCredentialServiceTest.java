package io.integrationplatform.config.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.application.CredentialAudit;
import io.integrationplatform.config.credential.application.TargetCredentialService;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository.EndpointOwner;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * E5-F1-S3 (T-103): the target-credential save's refusals before anything is sealed or written - every validation
 * error listed at once (400), and 503 when this instance has no active key-encryption key (ADR-007 Decision 7 rule 1:
 * the key comes only from the deployment's secret store; without it nothing can be sealed). No refusal echoes a value.
 */
@ExtendWith(OutputCaptureExtension.class)
class TargetCredentialServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CredentialTargetRepository repository = mock(CredentialTargetRepository.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();
    private final LoginBinding actor = new LoginBinding(UUID.randomUUID(), "ACTIVE", tenant, "tnt", "ACTIVE",
            ZoneId.of("Etc/UTC"));

    private TargetCredentialService service(Optional<String> activeKey) {
        KeyEncryptionKeys keys = new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> activeId() {
                return activeKey;
            }
        };
        return new TargetCredentialService(repository, TransactionOperations.withoutTransaction(), keys,
                new CredentialAudit());
    }

    @Test
    @Tag("negative")
    @DisplayName("Every validation error at once: an unknown field for the type, an empty value; no value is echoed")
    void everyValidationErrorListed(CapturedOutput output) {
        when(repository.findEndpoint(endpoint)).thenReturn(Optional.of(new EndpointOwner(endpoint, tenant, "MQ")));

        assertThatThrownBy(() -> service(Optional.of("K1")).save(endpoint.toString(), JSON.readTree(
                "{\"fields\":{\"apiKey\":\"leak-1\",\"queueManagerPassword\":\"\",\"nonsense\":\"leak-2\"}}"), actor))
                .isInstanceOf(ConfigApiException.class)
                .satisfies(e -> {
                    ConfigApiException refused = (ConfigApiException) e;
                    assertThat(refused.httpStatus()).isEqualTo(400);
                    assertThat(refused.body().toString())
                            .contains("fields.apiKey").contains("fields.nonsense")
                            .contains("fields.queueManagerPassword")
                            .contains("queueManagerPassword, tlsKeystorePassword, tlsTruststorePassword")
                            .doesNotContain("leak-1").doesNotContain("leak-2");
                });
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
        assertThat(output).doesNotContain("leak-1").doesNotContain("leak-2");
    }

    @Test
    @Tag("negative")
    @DisplayName("No fields object, or an empty one: 400")
    void noFields() {
        when(repository.findEndpoint(endpoint)).thenReturn(Optional.of(new EndpointOwner(endpoint, tenant, "API")));
        for (String body : new String[] {"{}", "{\"fields\":{}}", "{\"fields\":\"x\"}"}) {
            assertThatThrownBy(() -> service(Optional.of("K1")).save(endpoint.toString(), JSON.readTree(body), actor))
                    .as(body).isInstanceOf(ConfigApiException.class)
                    .satisfies(e -> assertThat(((ConfigApiException) e).httpStatus()).isEqualTo(400));
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("No active key-encryption key on this instance: 503, nothing sealed or written")
    void noActiveKeyIs503() {
        when(repository.findEndpoint(endpoint)).thenReturn(Optional.of(new EndpointOwner(endpoint, tenant, "API")));

        assertThatThrownBy(() -> service(Optional.empty()).save(endpoint.toString(),
                JSON.readTree("{\"fields\":{\"basicPassword\":\"not-sealed\"}}"), actor))
                .isInstanceOf(ConfigApiException.class)
                .satisfies(e -> {
                    ConfigApiException refused = (ConfigApiException) e;
                    assertThat(refused.httpStatus()).isEqualTo(503);
                    assertThat(refused.body().toString()).contains("key-encryption key")
                            .doesNotContain("not-sealed");
                });
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @Tag("negative")
    @DisplayName("An endpoint the tenant cannot see is 404; a malformed id is 404; an untyped system is 409")
    void endpointRefusals() {
        when(repository.findEndpoint(endpoint)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(Optional.of("K1")).save(endpoint.toString(),
                JSON.readTree("{\"fields\":{\"basicPassword\":\"x\"}}"), actor))
                .satisfies(e -> assertThat(((ConfigApiException) e).httpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service(Optional.of("K1")).read("not-a-uuid"))
                .satisfies(e -> assertThat(((ConfigApiException) e).httpStatus()).isEqualTo(404));

        UUID untyped = UUID.randomUUID();
        when(repository.findEndpoint(untyped)).thenReturn(Optional.of(new EndpointOwner(untyped, tenant, null)));
        assertThatThrownBy(() -> service(Optional.of("K1")).save(untyped.toString(),
                JSON.readTree("{\"fields\":{\"basicPassword\":\"x\"}}"), actor))
                .satisfies(e -> assertThat(((ConfigApiException) e).httpStatus()).isEqualTo(409));
    }
}
