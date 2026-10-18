package io.integrationplatform.app.composition;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The API adapter's request body from the transform port (T-049 composition; ADR-010 section 2 stage 2 "transforms
 * through the transform port", ADR-013, ADR-015): the delivery's PINNED mapping version (stage 1 pinned it from the
 * message's interface version - a later configuration change never alters it) is evaluated by the JSONata engine
 * (ADR-006, the 5 s budget of T-109) on the message's stored payload; the output is the body in the target's contract.
 * No pinned mapping, no payload, an engine other than JSONata, an engine error or the budget running out -> no body,
 * so the adapter refuses the send PERMANENT and nothing half-translated is ever dialled. The payload is never logged.
 *
 * <p>Runs inside the adapter call's tenant-stamped transaction: the reads are tenant-scoped, under row-level security.
 */
final class TransformedApiBody implements ApiTargetBody {

    private static final Logger log = LoggerFactory.getLogger(TransformedApiBody.class);

    private final Inputs inputs;
    private final TransformPort transform;
    private final LookupSource lookups;

    TransformedApiBody(JdbcClient jdbcClient, TenantContext tenantContext, TransformPort transform,
            LookupSource lookups) {
        this.inputs = new Inputs(jdbcClient, tenantContext);
        this.transform = Objects.requireNonNull(transform, "transform");
        this.lookups = Objects.requireNonNull(lookups, "lookups");
    }

    @Override
    public Optional<ApiWireBody> bodyFor(Stage2Send send) {
        Inputs.Input input = inputs.forDelivery(send.deliveryId()).orElse(null);
        if (input == null || input.expression() == null || input.payloadJson() == null) {
            log.warn("delivery {}: no transform input (no pinned mapping version or no stored payload)",
                    send.deliveryId());
            return Optional.empty();
        }
        if (!"JSONATA".equals(input.engine())) {
            log.warn("delivery {}: pinned mapping engine {} is not the ADR-006 engine", send.deliveryId(),
                    input.engine());
            return Optional.empty();
        }
        TransformPort.Result result = transform.transform(input.expression(), input.payloadJson(), lookups);
        if (result.status() != TransformPort.Status.OK || result.outputJson() == null) {
            log.warn("delivery {}: the pinned mapping version answered {}", send.deliveryId(), result.status());
            return Optional.empty();
        }
        log.info("delivery {}: transformed through its pinned mapping version", send.deliveryId());
        return Optional.of(new ApiWireBody("application/json", result.outputJson().getBytes(StandardCharsets.UTF_8)));
    }

    /** The delivery's pinned mapping version and its message's payload - one table in the FROM clause (R08). */
    static final class Inputs extends TenantScopedRepository {

        record Input(String expression, String engine, String payloadJson) {
        }

        private static final String SELECT_INPUT = """
                select (select mv.compiled_expression from public.mapping_version mv
                         where mv.id = d.mapping_version_id) as expression,
                       (select mv.engine from public.mapping_version mv
                         where mv.id = d.mapping_version_id) as engine,
                       (select p.body::text from public.payload p
                         where p.message_id = d.message_id and p.accepted_at = d.created_at) as payload
                from public.delivery d""";

        Inputs(JdbcClient jdbcClient, TenantContext tenantContext) {
            super(jdbcClient, tenantContext);
        }

        Optional<Input> forDelivery(UUID deliveryId) {
            List<Input> rows = query(SELECT_INPUT, "d.id = :id::uuid", Map.of("id", deliveryId.toString()),
                    (rs, n) -> new Input(rs.getString("expression"), rs.getString("engine"),
                            rs.getString("payload")));
            return rows.stream().findFirst();
        }
    }
}
