package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-125 (E10-S2): the endpoint row's MQ-CSV target - the endpoint's MQ system's stored registry settings
 * ({@code public.system}; the subquery pattern keeps the bare tenant predicate bound to {@code target_endpoint}
 * only), the CSV contract as the settings' {@code contract} section (same style as {@code MqSettings}, ADR-011
 * Decision 1), the optional {@code user} from the same JSON, and the queue-manager password from the credential
 * seam (opened only at send time, ADR-007 Decision 7 rule 6). A row the target cannot express (unparseable or
 * unvalidated settings) is a configuration bug thrown as {@link CsvContractException}, so {@code MqTargetCall}
 * refuses it PERMANENT, never transient.
 *
 * <p>shortcut: the contract travels inside the MQ system's registry JSON because {@code target_endpoint} does not
 * yet carry its own {@code type}/{@code settings} columns (E5's registry extension); move it to the endpoint row
 * when that migration lands.
 */
public class JdbcMqCsvTargets extends TenantScopedRepository implements MqCsvTargets {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The register name of the field the vault stores for MQ_CSV ({@code sealed.field()}), not an admin name. */
    public static final String QUEUE_MANAGER_PASSWORD_FIELD = "QUEUE_MANAGER_PASSWORD";

    private static final String SELECT_MQ_CSV_TARGET = "select (select s.settings::text from public.system s"
            + " where s.id = te.system_id and s.type = 'MQ') as v from public.target_endpoint te";

    /** The queue-manager password per endpoint, open (ADR-007 rule 6); the send path zeroes it after use. */
    private final Function<UUID, Optional<char[]>> password;

    public JdbcMqCsvTargets(JdbcClient jdbcClient, TenantContext tenantContext,
            Function<UUID, Optional<char[]>> password) {
        super(jdbcClient, tenantContext);
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    public Optional<MqCsvTarget> byEndpointId(UUID targetEndpointId) {
        return one(SELECT_MQ_CSV_TARGET, targetEndpointId).map(settings -> target(targetEndpointId, settings));
    }

    private MqCsvTarget target(UUID targetEndpointId, String settingsJson) {
        JsonNode settings;
        String contract;
        try {
            settings = JSON.readTree(settingsJson);
            contract = contractSection(settings);
        } catch (JacksonException malformed) { // a stored row we cannot read: the class name only, never its text
            throw new CsvContractException("the MQ-CSV target settings are unusable ("
                    + malformed.getClass().getSimpleName() + ")");
        }
        if (contract == null) {
            return null; // no stored contract: MqCsvOutbound refuses the send PERMANENT (empty target)
        }
        char[] opened = password.apply(targetEndpointId).orElse(null);
        try {
            return new MqCsvTarget(MqSettings.fromJson(settingsJson), user(settings), opened,
                    CsvContractSettings.fromJson(contract));
        } catch (JacksonException | IllegalStateException unusable) {
            if (opened != null) {
                Arrays.fill(opened, '\0'); // the send path's zeroing finally never runs: no MqMessage was built
            }
            throw new CsvContractException(unusable instanceof JacksonException
                    ? "the MQ-CSV target settings are unusable (" + unusable.getClass().getSimpleName() + ")"
                    : String.valueOf(unusable.getMessage()));
        }
    }

    private static String contractSection(JsonNode settings) {
        JsonNode contract = settings.get("contract");
        return contract != null && contract.isObject() ? contract.toString() : null;
    }

    private static String user(JsonNode settings) {
        JsonNode node = settings.get("user");
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private Optional<String> one(String select, UUID targetEndpointId) {
        return query(select, "te.id = :id::uuid", Map.of("id", targetEndpointId.toString()),
                (rs, rowNum) -> rs.getString("v")).stream().filter(Objects::nonNull).findFirst();
    }
}