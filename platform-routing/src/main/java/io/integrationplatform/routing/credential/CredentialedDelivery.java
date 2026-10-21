package io.integrationplatform.routing.credential;

import io.integrationplatform.config.credential.api.CredentialUnreadableException;
import io.integrationplatform.config.credential.api.KeyAvailability;
import io.integrationplatform.config.credential.api.KeyUnavailableException;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.domain.delivery.OutcomeClassifier;
import io.integrationplatform.domain.delivery.OutcomeClassifier.CredentialFault;
import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The send step's credential gate (E5 T-185; ADR-007 Decision 7 rules 5 and 6; ADR-010 sections 5 and 6): before a
 * claimed ({@code SENDING}) delivery calls its target, the target endpoint's credentials are opened - at send time,
 * here, for this call only - and the two decryption failures go two different ways:
 *
 * <ul>
 *   <li><b>unknown key-encryption-key id</b> (TRANSIENT): no call is made, so no attempt row and no attempt used up;
 *       the delivery moves {@code SENDING -> RETRY_WAIT} with a {@code KEY_UNAVAILABLE} event on the bulkhead-full
 *       backoff; an alert fires ({@code event=credential.key_unavailable alert=true}); the key id is reported to the
 *       readiness check, which takes this instance out of service;</li>
 *   <li><b>authentication-tag mismatch</b> (PERMANENT): no call; {@code SENDING -> DEAD_LETTERED} with the reason
 *       "target credential unreadable"; an alert fires ({@code event=credential.unreadable alert=true}).</li>
 * </ul>
 * In both cases only ids are logged - never ciphertext or plaintext. With readable credentials the call runs with the
 * values in memory and they are wiped when it returns; what the call's outcome does to the delivery stays the outcome
 * recorder's (the attempt executor, E10). Every state move is token-conditional (a stale claim moves nothing).
 */
public final class CredentialedDelivery {

    /** What the gate did. */
    public enum Kind {
        /** The call ran with the opened credentials; its value is in the result. */
        SENT,
        /** No call: the key is not on this instance - RETRY_WAIT, KEY_UNAVAILABLE, readiness told. */
        KEY_UNAVAILABLE,
        /** No call: a tag mismatch - DEAD_LETTERED "target credential unreadable". */
        UNREADABLE
    }

    /**
     * The gate's result.
     *
     * @param outcomeClass the credential fault's class (TRANSIENT, PERMANENT), {@code null} when the call ran
     * @param value        the call's own result, {@code null} when no call ran
     */
    public record Result<T>(Kind kind, OutcomeClass outcomeClass, T value) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(CredentialedDelivery.class);

    private final SendTimeCredentials credentials;
    private final KeyAvailability keyAvailability;
    private final DeliveryRetryStore deliveries;
    private final TransactionOperations transactions;
    private final DoubleSupplier jitter;
    private final Clock clock;
    private final MeterRegistry meters;

    public CredentialedDelivery(SendTimeCredentials credentials, KeyAvailability keyAvailability,
            DeliveryRetryStore deliveries, TransactionOperations transactions, DoubleSupplier jitter, Clock clock) {
        this(credentials, keyAvailability, deliveries, transactions, jitter, clock, null);
    }

    public CredentialedDelivery(SendTimeCredentials credentials, KeyAvailability keyAvailability,
            DeliveryRetryStore deliveries, TransactionOperations transactions, DoubleSupplier jitter, Clock clock,
            MeterRegistry meters) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.keyAvailability = Objects.requireNonNull(keyAvailability, "keyAvailability");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = meters;
    }

    /**
     * Opens the endpoint's credentials and runs {@code call} with them, or moves the delivery for a key fault.
     *
     * @param claimToken the token of the delivery's current claim (every move is conditional on it)
     */
    public <T> Result<T> send(UUID deliveryId, UUID claimToken, UUID targetEndpointId,
            Function<OpenedCredentials, T> call) {
        Objects.requireNonNull(call, "call");
        OpenedCredentials opened;
        try {
            opened = transactions.execute(status -> credentials.open(targetEndpointId));
        } catch (KeyUnavailableException missing) {
            return keyUnavailable(deliveryId, claimToken, targetEndpointId, missing.kekId());
        } catch (CredentialUnreadableException unreadable) {
            return unreadable(deliveryId, claimToken, targetEndpointId, unreadable);
        }
        try (OpenedCredentials values = opened) {
            return new Result<>(Kind.SENT, null, call.apply(values));
        }
    }

    private <T> Result<T> keyUnavailable(UUID deliveryId, UUID claimToken, UUID targetEndpointId, String kekId) {
        OutcomeClass outcome = OutcomeClassifier.ofCredentialFault(CredentialFault.KEY_UNAVAILABLE);
        LOG.atError()
                .addKeyValue("event", "credential.key_unavailable")
                .addKeyValue("alert", true)
                .addKeyValue("kekId", kekId)
                .addKeyValue("delivery", deliveryId)
                .addKeyValue("targetEndpoint", targetEndpointId)
                .log("event=credential.key_unavailable alert=true kekId={} delivery={} targetEndpoint={} - no call,"
                        + " the delivery waits (RETRY_WAIT) and this instance fails its readiness check", kekId,
                        deliveryId, targetEndpointId);
        keyAvailability.reportMissing(kekId);
        if (meters != null) {
            meters.counter("platform.delivery.key_unavailable").increment();
        }
        double fraction = jitter.getAsDouble();
        transactions.execute(status -> deliveries.deferKeyUnavailable(deliveryId, claimToken, clock.instant(),
                fraction));
        return new Result<>(Kind.KEY_UNAVAILABLE, outcome, null);
    }

    private <T> Result<T> unreadable(UUID deliveryId, UUID claimToken, UUID targetEndpointId,
            CredentialUnreadableException unreadable) {
        OutcomeClass outcome = OutcomeClassifier.ofCredentialFault(CredentialFault.TAG_MISMATCH);
        LOG.atError()
                .addKeyValue("event", "credential.unreadable")
                .addKeyValue("alert", true)
                .addKeyValue("credential", unreadable.credentialId())
                .addKeyValue("delivery", deliveryId)
                .addKeyValue("targetEndpoint", targetEndpointId)
                .log("event=credential.unreadable alert=true credential={} delivery={} targetEndpoint={} -"
                        + " dead-lettered: {}", unreadable.credentialId(), deliveryId, targetEndpointId,
                        CredentialUnreadableException.REASON);
        transactions.execute(status -> deliveries.deadLetter(deliveryId, claimToken, outcome,
                CredentialUnreadableException.REASON));
        return new Result<>(Kind.UNREADABLE, outcome, null);
    }
}
