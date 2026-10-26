package io.integrationplatform.config.rule.internal;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rule_set and lookup_table rows (T-023, V2_2_50/V2_2_51). Every statement goes through
 * {@link TenantScopedRepository} (R08: the tenant predicate on every statement, R09: {@code ::uuid} casts, R11: inside
 * the caller's transaction); inserts use the {@code with ins as (insert ... returning) select} shape so the tenant
 * predicate filters even a write's answer. RLS on both tables is the second wall.
 */
public final class JdbcRuleSetRepository extends TenantScopedRepository {

    /** One rule set; {@code content} is the JSON exactly as authored ({@code content_text}). */
    public record RuleSetRow(String id, String flowId, String kind, String name, String content) {
    }

    /** A rule set read together with the fingerprint of its stored content (the evaluation cache's validity check). */
    public record Fingerprinted(RuleSetRow row, String fingerprint) {
    }

    private record Listed(Timestamp createdAt, RuleSetRow row) {
    }

    // ------------------------------------------------------------------ sql (compile-time constants, R11)

    private static final String FLOW_EXISTS = "select 1 as present from public.interface i";

    private static final String READ = "select r.id::text as id, r.interface_id::text as flow_id, r.kind, r.name,"
            + " r.content_text, r.created_at from public.rule_set r";

    /** md5 of the content exactly as stored: it changes whenever the stored text changes, by any writer. */
    private static final String FINGERPRINT = "select md5(r.content_text) as fingerprint from public.rule_set r";

    private static final String READ_FINGERPRINTED = "select r.id::text as id, r.interface_id::text as flow_id, r.kind,"
            + " r.name, r.content_text, r.created_at, md5(r.content_text) as fingerprint from public.rule_set r";

    private static final String INSERT = "with ins as ("
            + "insert into public.rule_set (tenant_id, interface_id, kind, name, content, content_text, author_id)"
            + " values (current_setting('app.tenant_id')::uuid, :flow_id::uuid, :kind, :name, :content::jsonb,"
            + "    :content, :author_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String UPDATE = "update public.rule_set set name = :name, content = :content::jsonb,"
            + " content_text = :content, author_id = :author_id::uuid, updated_at = now()";

    private static final String INSERT_LOOKUP = "with ins as ("
            + "insert into public.lookup_table (tenant_id, rule_set_id, name)"
            + " values (current_setting('app.tenant_id')::uuid, :rule_set_id::uuid, :name)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String RENAME_LOOKUP = "update public.lookup_table set name = :name, updated_at = now()";

    private static final RowMapper<Listed> ROW = (rs, n) -> new Listed(rs.getTimestamp("created_at"),
            new RuleSetRow(rs.getString("id"), rs.getString("flow_id"), rs.getString("kind"), rs.getString("name"),
                    rs.getString("content_text")));

    private static final RowMapper<Fingerprinted> FINGERPRINTED = (rs, n) -> new Fingerprinted(
            ROW.mapRow(rs, n).row(), rs.getString("fingerprint"));

    private static final RowMapper<String> FIRST_COLUMN = (rs, n) -> rs.getString(1);

    public JdbcRuleSetRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty())); // fail closed without the identity chain
    }

    public boolean flowExists(UUID flowId) {
        return !query(FLOW_EXISTS, "i.id = :id::uuid", Map.of("id", flowId.toString()),
                (rs, n) -> rs.getInt("present")).isEmpty();
    }

    public Optional<RuleSetRow> find(UUID ruleSetId) {
        return query(READ, "r.id = :id::uuid", Map.of("id", ruleSetId.toString()), ROW).stream()
                .findFirst().map(Listed::row);
    }

    /**
     * The fingerprint of the stored content alone - one narrow row, no content transferred. Empty when the bound tenant
     * cannot see the id.
     */
    public Optional<String> fingerprint(UUID ruleSetId) {
        return query(FINGERPRINT, "r.id = :id::uuid", Map.of("id", ruleSetId.toString()),
                (rs, n) -> rs.getString("fingerprint")).stream().findFirst();
    }

    /** The rule set and the fingerprint of the very content read, in one statement (so the two never disagree). */
    public Optional<Fingerprinted> findFingerprinted(UUID ruleSetId) {
        return query(READ_FINGERPRINTED, "r.id = :id::uuid", Map.of("id", ruleSetId.toString()), FINGERPRINTED)
                .stream().findFirst();
    }

    /** The flow's rule sets in authoring order. */
    public List<RuleSetRow> listByFlow(UUID flowId) {
        return query(READ, "r.interface_id = :flow_id::uuid", Map.of("flow_id", flowId.toString()), ROW).stream()
                .sorted(Comparator.comparing(Listed::createdAt).thenComparing(listed -> listed.row().id()))
                .map(Listed::row)
                .toList();
    }

    public String insert(UUID flowId, String kind, String name, String content, UUID authorId) {
        return single(query(INSERT, null, Map.of("flow_id", flowId.toString(), "kind", kind, "name", name,
                "content", content, "author_id", authorId.toString()), FIRST_COLUMN));
    }

    public int update(UUID ruleSetId, String name, String content, UUID authorId) {
        return update(UPDATE, "id = :id::uuid", Map.of("id", ruleSetId.toString(), "name", name,
                "content", content, "author_id", authorId.toString()));
    }

    public String insertLookupTable(String ruleSetId, String name) {
        return single(query(INSERT_LOOKUP, null, Map.of("rule_set_id", ruleSetId, "name", name), FIRST_COLUMN));
    }

    public int renameLookupTable(UUID ruleSetId, String name) {
        return update(RENAME_LOOKUP, "rule_set_id = :rule_set_id::uuid",
                Map.of("rule_set_id", ruleSetId.toString(), "name", name));
    }

    private static String single(List<String> rows) {
        if (rows.size() != 1) {
            throw new IllegalStateException("the insert returned " + rows.size() + " rows for the bound tenant");
        }
        return rows.getFirst();
    }
}
