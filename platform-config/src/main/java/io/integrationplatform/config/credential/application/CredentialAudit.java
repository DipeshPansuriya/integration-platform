package io.integrationplatform.config.credential.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit of every credential action (T-103; R14): one structured event per action, ids only - tenant, actor, system
 * or endpoint, credential ids, field names and outcome; never a secret, a ciphertext, a wrapped key or a client secret.
 * The platform audit store (platform-audit) does not exist yet, so the log line is the record (the TestConnectionAudit
 * style).
 */
public class CredentialAudit {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialAudit.class);

    private final ApplicationEventPublisher events;
    private final TransactionOperations writeTransaction;

    /**
     * @param events           where a completed action publishes its audit event (E12-F2-S1, T-134)
     * @param writeTransaction the write transaction the event is published in (joined when one is open), so the
     *                         AFTER_COMMIT audit writer receives it - the source services make Keycloak calls and
     *                         are deliberately not {@code @Transactional}
     */
    public CredentialAudit(ApplicationEventPublisher events, TransactionOperations writeTransaction) {
        this.events = Objects.requireNonNull(events, "events");
        this.writeTransaction = Objects.requireNonNull(writeTransaction, "writeTransaction");
    }

    /** ponytail: the log-line-only audit the E5 unit and container tests compose by hand (no event, no store). */
    public CredentialAudit() {
        this(event -> { }, TransactionOperations.withoutTransaction());
    }

    public void sourceIssued(UUID tenant, UUID actor, UUID system, UUID credential, String clientId) {
        LOG.atInfo().addKeyValue("event", "credential.source_issued").addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor).addKeyValue("system", system).addKeyValue("credential", credential)
                .addKeyValue("clientId", clientId)
                .log("event=credential.source_issued tenant={} system={} credential={} clientId={}", tenant, system,
                        credential, clientId);
    }

    public void sourceRotated(UUID tenant, UUID actor, UUID replaced, UUID replacement) {
        LOG.atInfo().addKeyValue("event", "credential.source_rotated").addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor).addKeyValue("credential", replaced)
                .addKeyValue("replacement", replacement)
                .log("event=credential.source_rotated tenant={} credential={} replacement={}", tenant, replaced,
                        replacement);
    }

    public void sourceRevoked(UUID tenant, UUID actor, UUID credential, String outcome) {
        LOG.atInfo().addKeyValue("event", "credential.source_revoked").addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor).addKeyValue("credential", credential).addKeyValue("outcome", outcome)
                .log("event=credential.source_revoked tenant={} credential={} outcome={}", tenant, credential,
                        outcome);
    }

    public void sourceRefused(UUID tenant, UUID actor, UUID system, String reason) {
        LOG.atWarn().addKeyValue("event", "credential.source_refused").addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor).addKeyValue("system", system).addKeyValue("reason", reason)
                .log("event=credential.source_refused tenant={} system={} reason={}", tenant, system, reason);
    }

    public void targetSaved(UUID tenant, UUID actor, UUID endpoint, List<UUID> credentials, List<String> fields) {
        LOG.atInfo().addKeyValue("event", "credential.target_saved").addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor).addKeyValue("endpoint", endpoint)
                .addKeyValue("credentials", credentials).addKeyValue("fields", fields)
                .log("event=credential.target_saved tenant={} endpoint={} credentials={} fields={}", tenant, endpoint,
                        credentials, fields);
    }
}
