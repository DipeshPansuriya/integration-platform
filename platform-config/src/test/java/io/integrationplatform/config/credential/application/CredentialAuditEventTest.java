package io.integrationplatform.config.credential.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E12-F2-S1 "One event per action" for credentials (row 327 T117, T-134): issuing, rotating and revoking a source
 * credential and saving a target credential each publish exactly one {@link AuditEvent} - ids, outcome and field
 * names only, never a secret or the client id - and publish it INSIDE a write transaction: the credential services
 * are not {@code @Transactional} (Keycloak admin calls), and the append-only writer listens AFTER_COMMIT, so an
 * event published outside a transaction would be dropped. A refusal stays the log line and publishes no event.
 */
class CredentialAuditEventTest {

    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID SYSTEM = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID CREDENTIAL = UUID.fromString("14141414-1414-1414-1414-141414141414");
    private static final UUID REPLACEMENT = UUID.fromString("15151515-1515-1515-1515-151515151515");
    private static final UUID ENDPOINT = UUID.fromString("16161616-1616-1616-1616-161616161616");

    /** Counts the write transactions opened and whether each publish ran inside one. */
    private static final class RecordingTransactions implements TransactionOperations {

        int opened;
        boolean inside;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            opened++;
            inside = true;
            try {
                TransactionStatus status = new SimpleTransactionStatus(true);
                return action.doInTransaction(status);
            } finally {
                inside = false;
            }
        }
    }

    private final RecordingTransactions transactions = new RecordingTransactions();
    private final List<AuditEvent> published = new ArrayList<>();
    private final List<Boolean> publishedInsideTransaction = new ArrayList<>();
    private final CredentialAudit audit = new CredentialAudit(event -> {
        published.add((AuditEvent) event);
        publishedInsideTransaction.add(transactions.inside);
    }, transactions);

    private AuditEvent theOneEvent(Consumer<CredentialAudit> action) {
        action.accept(audit);
        assertThat(published).as("exactly one audit event for the completed action").hasSize(1);
        assertThat(publishedInsideTransaction).as("published inside a write transaction").containsExactly(true);
        assertThat(transactions.opened).as("one write transaction").isEqualTo(1);
        AuditEvent event = published.getFirst();
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.at()).isNotNull();
        return event;
    }

    @Test
    void anIssuedSourceCredentialPublishesOneEventWithoutTheClientId() {
        AuditEvent event = theOneEvent(a -> a.sourceIssued(TENANT, ACTOR, SYSTEM, CREDENTIAL, "client-abc"));

        assertThat(event.action()).isEqualTo(ConfigAuditActions.CREDENTIAL_SOURCE_ISSUED);
        assertThat(event.targetIds()).containsExactly("system:" + SYSTEM, "credential:" + CREDENTIAL);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"status\":\"ACTIVE\"}");
        assertThat(event.toString()).doesNotContain("client-abc");
    }

    @Test
    void aRotatedSourceCredentialPublishesOneEventNamingBothCredentials() {
        AuditEvent event = theOneEvent(a -> a.sourceRotated(TENANT, ACTOR, CREDENTIAL, REPLACEMENT));

        assertThat(event.action()).isEqualTo(ConfigAuditActions.CREDENTIAL_SOURCE_ROTATED);
        assertThat(event.targetIds()).containsExactly("credential:" + CREDENTIAL, "credential:" + REPLACEMENT);
        assertThat(event.before()).isNull();
        assertThat(event.after()).as("the replaced credential stays ACTIVE until its revoke-after; the direction")
                .isEqualTo("{\"replacement\":\"" + REPLACEMENT + "\"}");
    }

    @Test
    void aRevokedSourceCredentialPublishesOneEventWithItsOutcome() {
        AuditEvent event = theOneEvent(a -> a.sourceRevoked(TENANT, ACTOR, CREDENTIAL, "IDP_DISABLE_PENDING"));

        assertThat(event.action()).isEqualTo(ConfigAuditActions.CREDENTIAL_SOURCE_REVOKED);
        assertThat(event.targetIds()).containsExactly("credential:" + CREDENTIAL);
        assertThat(event.after()).isEqualTo("{\"status\":\"REVOKED\",\"outcome\":\"IDP_DISABLE_PENDING\"}");
    }

    @Test
    void aSavedTargetCredentialPublishesOneEventWithFieldNamesOnly() {
        AuditEvent event = theOneEvent(a -> a.targetSaved(TENANT, ACTOR, ENDPOINT, List.of(CREDENTIAL, REPLACEMENT),
                List.of("username", "password")));

        assertThat(event.action()).isEqualTo(ConfigAuditActions.CREDENTIAL_TARGET_SAVED);
        assertThat(event.targetIds()).containsExactly("endpoint:" + ENDPOINT, "credential:" + CREDENTIAL,
                "credential:" + REPLACEMENT);
        assertThat(event.before()).isNull();
        assertThat(event.after()).isEqualTo("{\"fields\":[\"username\",\"password\"]}");
    }

    @Test
    void aRefusalIsNotAnActionAndPublishesNothing() {
        audit.sourceRefused(TENANT, ACTOR, SYSTEM, "identity_provider_unavailable");

        assertThat(published).isEmpty();
        assertThat(transactions.opened).isZero();
    }
}
