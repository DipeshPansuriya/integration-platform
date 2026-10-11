package io.integrationplatform.config.credential.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.api.TargetCredentialField;
import io.integrationplatform.config.credential.api.TargetCredentialType;
import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository.EndpointOwner;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository.StoredCredential;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

/**
 * E5-F1-S3 / E5-F1-S4 (T-103, T-185; ADR-007 Decision 7): the target credentials of a target endpoint - write-only.
 *
 * <ul>
 *   <li><b>Save</b> ({@code {"fields": {"basicPassword": "...", ...}}}): every field named by the endpoint's target type
 *       (rule 10: the system's registered type - API, MQ (the MQ_CSV target), DB, FILE) becomes its own row, sealed with
 *       its own data key under the ACTIVE key-encryption key (rules 1-3); a field saved again keeps its row id and is
 *       re-sealed (rule 2). Every validation error is answered at once (400) and none echoes a value.</li>
 *   <li><b>Read</b>: which fields are set, each with its credential id and the masked marker {@value #MASK} - never the
 *       value, the ciphertext or the wrapped key (rule 8).</li>
 * </ul>
 * One transaction per call (R11). Refusals in order: 404 (malformed id, or the endpoint is not the bound tenant's), 409
 * (the system has no type yet), 400 (validation), 503 (no active key-encryption key on this instance). The audit event
 * names the credential ids and field names only.
 */
public class TargetCredentialService {

    /** The masked marker every read shows instead of a value. */
    public static final String MASK = "********";

    /** A private key in PEM is the longest value a field holds. */
    static final int MAX_VALUE_LENGTH = 16_384;

    private final CredentialTargetRepository targets;
    private final TransactionOperations transactions;
    private final KeyEncryptionKeys keys;
    private final CredentialAudit audit;

    public TargetCredentialService(CredentialTargetRepository targets, TransactionOperations transactions,
            KeyEncryptionKeys keys, CredentialAudit audit) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Map<String, Object> read(String endpointId) {
        UUID endpoint = parse(endpointId);
        return transactions.execute(status -> {
            EndpointOwner owner = targets.findEndpoint(endpoint).orElseThrow(ConfigApiException::notFound);
            return view(owner, targets.listForEndpoint(endpoint));
        });
    }

    public Map<String, Object> save(String endpointId, JsonNode body, LoginBinding actor) {
        UUID endpoint = parse(endpointId);
        Objects.requireNonNull(actor, "actor");
        return transactions.execute(status -> {
            EndpointOwner owner = targets.findEndpoint(endpoint).orElseThrow(ConfigApiException::notFound);
            TargetCredentialType type = TargetCredentialType.ofSystemType(owner.systemType())
                    .orElseThrow(() -> ConfigApiException.conflict("the target system has no type yet; register"
                            + " its type (API, DB, MQ or FILE) before saving its credentials"));
            Map<TargetCredentialField, String> values = validate(type, body);
            String kekId = keys.activeId().orElseThrow(() -> CredentialApiException.unavailable("no active"
                    + " key-encryption key is configured on this instance (PLATFORM_CREDENTIAL_KEK_ACTIVE); target"
                    + " secrets cannot be saved"));
            SecretKey kek = keys.find(kekId).orElseThrow(() -> CredentialApiException.unavailable("the active"
                    + " key-encryption key is not on this instance; target secrets cannot be saved"));
            List<UUID> saved = new ArrayList<>();
            values.forEach((field, value) -> saved.add(seal(owner, type, field, value, kekId, kek)));
            audit.targetSaved(owner.tenantId(), actor.userId(), endpoint, saved,
                    values.keySet().stream().map(TargetCredentialField::name).toList());
            return view(owner, targets.listForEndpoint(endpoint));
        });
    }

    /** One field: its row id kept (or a new one), a fresh data key and nonces, sealed under the active key. */
    private UUID seal(EndpointOwner owner, TargetCredentialType type, TargetCredentialField field, String value,
            String kekId, SecretKey kek) {
        Optional<UUID> existing = targets.findId(owner.endpointId(), field.name());
        UUID id = existing.orElseGet(UUID::randomUUID);
        // ponytail: the JSON body already holds the value as a String (immutable); the char[] copy is wiped here
        char[] plaintext = value.toCharArray();
        try {
            EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(plaintext, owner.tenantId(), id, owner.endpointId(),
                    field.name(), kekId, kek);
            if (existing.isPresent()) {
                if (targets.reseal(id, kekId, sealed.wrappedKey(), sealed.ciphertext()) != 1) {
                    throw new IllegalStateException("the target credential row changed during the save");
                }
            } else {
                targets.insert(id, owner.endpointId(), type.name(), field.name(), kekId, sealed.wrappedKey(),
                        sealed.ciphertext());
            }
            return id;
        } finally {
            Arrays.fill(plaintext, '\0');
        }
    }

    private static Map<TargetCredentialField, String> validate(TargetCredentialType type, JsonNode body) {
        JsonNode fields = body == null ? null : body.get("fields");
        if (fields == null || !fields.isObject() || fields.isEmpty()) {
            throw CredentialApiException.invalid(List.of("fields is required: an object of one or more credential"
                    + " fields (" + allowed(type) + ")"));
        }
        List<String> errors = new ArrayList<>();
        Map<TargetCredentialField, String> values = new LinkedHashMap<>();
        for (String name : fields.propertyNames()) {
            Optional<TargetCredentialField> field = TargetCredentialField.ofJsonName(name)
                    .filter(known -> type.fields().contains(known));
            JsonNode value = fields.get(name);
            if (field.isEmpty()) {
                errors.add("fields." + name + " is not a credential field of a " + type.name() + " target (one of "
                        + allowed(type) + ")");
            } else if (!value.isString() || value.stringValue().isBlank()
                    || value.stringValue().length() > MAX_VALUE_LENGTH) {
                errors.add("fields." + name + " must be a non-empty text of at most " + MAX_VALUE_LENGTH
                        + " characters");
            } else {
                values.put(field.get(), value.stringValue());
            }
        }
        if (!errors.isEmpty()) {
            throw CredentialApiException.invalid(errors);
        }
        return values;
    }

    private static String allowed(TargetCredentialType type) {
        return type.fields().stream().map(TargetCredentialField::jsonName).collect(Collectors.joining(", "));
    }

    private static Map<String, Object> view(EndpointOwner owner, List<StoredCredential> stored) {
        Optional<TargetCredentialType> type = TargetCredentialType.ofSystemType(owner.systemType());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (StoredCredential credential : stored) {
            String name = Arrays.stream(TargetCredentialField.values())
                    .filter(field -> field.name().equals(credential.field())).findFirst()
                    .map(TargetCredentialField::jsonName).orElse(credential.field());
            fields.add(ConfigApiException.body("field", name, "credentialId", credential.id().toString(),
                    "value", MASK, "updatedAt", credential.updatedAt().toString()));
        }
        return ConfigApiException.body(
                "targetEndpointId", owner.endpointId().toString(),
                "targetType", type.map(TargetCredentialType::name).orElse(null),
                "fields", fields,
                "allowedFields", type.map(t -> t.fields().stream().map(TargetCredentialField::jsonName).toList())
                        .orElse(List.of()));
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw ConfigApiException.notFound();
        }
    }
}
