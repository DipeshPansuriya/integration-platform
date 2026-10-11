package io.integrationplatform.config.credential.crypto;

import io.integrationplatform.config.credential.api.CredentialAad;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

/**
 * ADR-007 Decision 7 rule 1 (T-103): the key-encryption keys from the deployment's secret store, read once at start-up
 * through the environment-variable NAMES {@value #PREFIX}{@code <ID>} (32 bytes, base64; ID = upper-case letters and
 * digits) with the sealing key named by {@value #ACTIVE}. Any property source of the environment can carry a name (the
 * OS environment in a deployment; a test's in-memory property source) - never a file in the repository, never a table.
 *
 * <p>A malformed key (not base64, not 32 bytes) is ignored and reported by its variable NAME only. The keys live in
 * memory; {@link #toString()} lists ids only.
 */
public final class EnvironmentKeyEncryptionKeys implements KeyEncryptionKeys {

    /** The variable-name prefix of a key-encryption key. */
    public static final String PREFIX = "PLATFORM_CREDENTIAL_KEK_";

    /** The variable naming the id new values are sealed with. */
    public static final String ACTIVE = PREFIX + "ACTIVE";

    private static final Pattern ID = Pattern.compile("[A-Z0-9]{1,32}");
    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentKeyEncryptionKeys.class);

    private final Map<String, SecretKey> keys;
    private final Optional<String> activeId;

    public EnvironmentKeyEncryptionKeys(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        Map<String, SecretKey> found = new TreeMap<>();
        for (String name : keyNames(environment)) {
            String id = name.substring(PREFIX.length());
            if (id.equals("ACTIVE") || !ID.matcher(id).matches()) {
                continue;
            }
            decode(name, environment.getProperty(name)).ifPresent(key -> found.put(id, key));
        }
        this.keys = Map.copyOf(found);
        String active = environment.getProperty(ACTIVE);
        this.activeId = active == null || !keys.containsKey(active.strip()) ? Optional.empty()
                : Optional.of(active.strip());
        if (active != null && activeId.isEmpty()) {
            LOG.atWarn().addKeyValue("event", "credential.kek_active_missing").addKeyValue("variable", ACTIVE)
                    .log("event=credential.kek_active_missing variable={} names a key this instance does not hold;"
                            + " target secrets cannot be saved", ACTIVE);
        }
    }

    @Override
    public Optional<SecretKey> find(String kekId) {
        return Optional.ofNullable(kekId == null ? null : keys.get(kekId));
    }

    @Override
    public Optional<String> activeId() {
        return activeId;
    }

    /** The ids this instance holds (no key material). */
    public Set<String> presentIds() {
        return keys.keySet();
    }

    @Override
    public String toString() {
        return "EnvironmentKeyEncryptionKeys" + keys.keySet() + " active=" + activeId.orElse("-");
    }

    private static Set<String> keyNames(Environment environment) {
        Set<String> names = new java.util.TreeSet<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            configurable.getPropertySources().forEach(source -> {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    Arrays.stream(enumerable.getPropertyNames()).filter(name -> name.startsWith(PREFIX))
                            .forEach(names::add);
                }
            });
        }
        return names;
    }

    private static Optional<SecretKey> decode(String name, String value) {
        byte[] bytes = null;
        try {
            bytes = value == null ? null : Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException notBase64) {
            bytes = null;
        }
        if (bytes == null || bytes.length != CredentialAad.KEY_BYTES) {
            LOG.atWarn().addKeyValue("event", "credential.kek_malformed").addKeyValue("variable", name)
                    .log("event=credential.kek_malformed variable={} is not 32 bytes of base64 and is ignored", name);
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            return Optional.empty();
        }
        SecretKey key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
        return Optional.of(key);
    }
}
