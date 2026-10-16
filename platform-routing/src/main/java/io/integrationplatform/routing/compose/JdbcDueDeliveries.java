package io.integrationplatform.routing.compose;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What stage 2 reads before it claims (T-049 composition): the claimable delivery's route, target endpoint and
 * interface, and its ordering key - the message's business key when the interface orders PER_KEY, none for NONE (the
 * claim's advisory lock, ADR-011 Decision 6). Tenant-scoped: the delivery is the only table in the FROM clause, the
 * message and interface are reached by sub-selects under their own row-level security.
 */
public class JdbcDueDeliveries extends TenantScopedRepository {

    /** A delivery waiting for its send. */
    public record DueDelivery(UUID id, UUID routeId, UUID targetEndpointId, UUID interfaceId, String orderingKey) {
    }

    private static final String SELECT_DUE = """
            select d.id, d.route_id, d.target_id,
                   (select m.interface_id from public.message m
                     where m.id = d.message_id and m.accepted_at = d.created_at) as interface_id,
                   (select case when i.ordering = 'PER_KEY' then m.business_key end
                      from public.message m join public.interface i on i.id = m.interface_id
                     where m.id = d.message_id and m.accepted_at = d.created_at) as ordering_key
            from public.delivery d""";

    public JdbcDueDeliveries(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The delivery while it is claimable ({@code PENDING} or {@code DUE}); empty once claimed, held or final. */
    public Optional<DueDelivery> due(UUID deliveryId) {
        List<DueDelivery> rows = query(SELECT_DUE, "d.id = :id::uuid and d.state in ('PENDING', 'DUE')",
                Map.of("id", deliveryId.toString()),
                (rs, n) -> new DueDelivery(rs.getObject("id", UUID.class), rs.getObject("route_id", UUID.class),
                        rs.getObject("target_id", UUID.class), rs.getObject("interface_id", UUID.class),
                        rs.getString("ordering_key")));
        return rows.stream().findFirst();
    }
}
