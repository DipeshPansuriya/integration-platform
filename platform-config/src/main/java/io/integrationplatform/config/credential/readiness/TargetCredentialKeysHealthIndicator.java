package io.integrationplatform.config.credential.readiness;

import io.integrationplatform.config.credential.api.KeyAvailability;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * ADR-007 Decision 7 rule 5 / ADR-010 section 9 item 4 (T-185, S-d): an instance that lacks a key-encryption key a
 * target credential needs is pulled out of service. The readiness group (config-secrets.yml) includes this check; it
 * answers OUT_OF_SERVICE (the group's HTTP answer is 503) while any key id that a credential row references (the
 * start-up check, read again on every probe so it holds from the first one) or that a delivery found missing (the
 * send-time report) is absent from this instance's keyring. Liveness never includes it: the fix is a redeploy with the
 * key, not a restart.
 *
 * <p>Fail closed: when the referenced ids cannot be read, the instance is not ready, and the next probe asks again. The
 * details name key ids only - never key material.
 */
public final class TargetCredentialKeysHealthIndicator implements HealthIndicator, KeyAvailability {

    private static final Logger LOG = LoggerFactory.getLogger(TargetCredentialKeysHealthIndicator.class);

    private final KeyEncryptionKeys keys;
    private final CredentialKeyReferences references;
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    public TargetCredentialKeysHealthIndicator(KeyEncryptionKeys keys, CredentialKeyReferences references) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.references = Objects.requireNonNull(references, "references");
    }

    @Override
    public void reportMissing(String kekId) {
        if (kekId != null && reportedMissing.add(kekId)) {
            LOG.atError()
                    .addKeyValue("event", "credential.instance_not_ready")
                    .addKeyValue("alert", true)
                    .addKeyValue("kekId", kekId)
                    .log("event=credential.instance_not_ready alert=true kekId={} - readiness fails until this"
                            + " instance holds the key", kekId);
        }
    }

    @Override
    public Health health() {
        Set<String> needed = new TreeSet<>(reportedMissing);
        try {
            needed.addAll(references.referencedKeyIds());
        } catch (RuntimeException unreadable) {
            return Health.status(Status.OUT_OF_SERVICE)
                    .withDetail("reason", "the start-up key check could not read the referenced key ids")
                    .build();
        }
        Set<String> missing = new TreeSet<>();
        for (String id : needed) {
            if (keys.find(id).isEmpty()) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return Health.up().build();
        }
        return Health.status(Status.OUT_OF_SERVICE).withDetail("missingKeyIds", java.util.List.copyOf(missing))
                .build();
    }
}
