package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository.ApprovedTableRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * E16 F3 (design note 2026-10-22, option B; Dipesh D1-D4): the tables a DB target may write to are requested by a
 * configurator and approved by a DIFFERENT tenant approver - never listed by the person who picks the table in the
 * system's settings. A request is a PENDING_APPROVAL row; approve and revoke run in the database's definer function
 * (V2_4_1330, replaced by V2_4_1420), which since the single-user change lets the requester approve their own
 * request - one tenant admin can do both; the width probe and the target-changed check still bind every approval. Each
 * completed action publishes one {@code AuditEvent} (system id, row id, table name and status - nothing else) inside
 * its transaction; a refusal rolls that back, so its record is the controller's log line.
 */
public class ApprovedTableService {

    private final JdbcApprovedTableRepository tables;
    private final JdbcSystemRepository systems;
    private final ApplicationEventPublisher events;
    private final Supplier<Optional<UUID>> tenant;
    private final DbAccountWidthCheck width;
    private final TransactionOperations transactions;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(ApprovedTableService.class);

    /** L19: at most one refusal event per (approval row, refusal code) per hour (audit_event is append-only, 7 years). */
    static final Duration REFUSAL_WINDOW = Duration.ofHours(1);
    private static final int THROTTLE_SWEEP_AT = 5000;
    /** The fixed privilege vocabulary of the width refusal (routing's probe); anything else never reaches the ledger. */
    private static final Set<String> PRIVILEGE_TOKENS = Set.of("DELETE", "TRUNCATE", "TRIGGER", "UPDATE", "REFERENCES",
            "BYPASSRLS", "SUPERUSER", "CREATEROLE", "CREATEDB", "REPLICATION", "DATABASE_CREATE", "ANY_SCHEMA_CREATE",
            "SCHEMA_CREATE", "OTHER_TABLE_PRIVILEGES");
    private static final Pattern PREDEFINED_ROLE = Pattern.compile("PG_[A-Z0-9_]{1,60}");
    private static final Pattern RIGHTS_LIST = Pattern.compile("rights \\(([^)]*)\\)");

    private final Clock clock;
    private final TransactionOperations auditTransactions;
    private final ConcurrentHashMap<String, Instant> refusalsWritten = new ConcurrentHashMap<>();

    public ApprovedTableService(JdbcApprovedTableRepository tables, JdbcSystemRepository systems,
            ApplicationEventPublisher events, Supplier<Optional<UUID>> tenant, DbAccountWidthCheck width,
            TransactionOperations transactions) {
        this(tables, systems, events, tenant, width, transactions, Clock.systemUTC());
    }

    public ApprovedTableService(JdbcApprovedTableRepository tables, JdbcSystemRepository systems,
            ApplicationEventPublisher events, Supplier<Optional<UUID>> tenant, DbAccountWidthCheck width,
            TransactionOperations transactions, Clock clock) {
        this(tables, systems, events, tenant, width, transactions, clock, transactions);
    }

    /** {@code auditTransactions} must open a NEW transaction (PROPAGATION_REQUIRES_NEW): the refusal event is durable. */
    public ApprovedTableService(JdbcApprovedTableRepository tables, JdbcSystemRepository systems,
            ApplicationEventPublisher events, Supplier<Optional<UUID>> tenant, DbAccountWidthCheck width,
            TransactionOperations transactions, Clock clock, TransactionOperations auditTransactions) {
        this.auditTransactions = Objects.requireNonNull(auditTransactions, "auditTransactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.width = Objects.requireNonNull(width, "width");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.systems = Objects.requireNonNull(systems, "systems");
        this.events = Objects.requireNonNull(events, "events");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
    }

    /** The system's approved-table rows; 404 for an unknown system. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String systemId) {
        requireSystem(systemId, false);
        return tables.list(systemId).stream().map(ApprovedTableService::view).toList();
    }

    /** A configurator asks for a table: 400 for a bad name or a non-DB system, 404 unknown system, 409 duplicate. */
    @Transactional
    public Map<String, Object> request(String systemId, JsonNode body, UUID actorId) {
        requireSystem(systemId, true);
        JsonNode field = body == null ? null : body.get("table");
        if (field == null || !field.isString() || !SystemSettings.isIdentifier(field.stringValue())) {
            throw ConfigApiException.validation("table is required: a plain table name (letters, digits, underscore)");
        }
        String table = field.stringValue();
        String id;
        try {
            id = tables.insertRequest(systemId, table, actorId.toString());
        } catch (DuplicateKeyException duplicate) {
            throw ConfigApiException.conflict("the table " + table + " is already requested or approved for this system");
        }
        ApprovedTableRow created = tables.find(id)
                .orElseThrow(() -> new IllegalStateException("the requested approved table vanished"));
        audit(ConfigAuditActions.DB_TABLE_REQUESTED, actorId, created, null);
        return view(created);
    }

    /**
     * Approves ({@code APPROVE}) or revokes ({@code REVOKE}) a row of the system. 404 for a row of another system or
     * tenant, 409 NOT_PENDING_APPROVAL / NOT_APPROVED. The requester may approve their own request.
     *
     * <p>T178a-1: three steps, in this order. (1) A short transaction reads the system and the row. (2) A real approval
     * asks the partner database about the account's width with NO platform transaction open - the probe dials for up
     * to 10 s and must not hold a pooled platform connection (the T-104 rule). (3) A second short transaction decides
     * and audits. The decision function still checks the state and the target itself, so a row that changed
     * between (1) and (3) is refused there.
     */
    public Map<String, Object> decide(String systemId, String id, String decision, UUID actorId) {
        Preflight pre = transactions.execute(status -> preflight(systemId, id, decision));
        if (pre.probe() != null) {
            // T178a: a real approval only (the state is checked first)
            Optional<String> refusal = widthRefusal(pre.probe());
            if (refusal.isPresent()) {
                String code = refusal.get().startsWith("DB_ACCOUNT_TOO_WIDE") ? "DB_ACCOUNT_TOO_WIDE"
                        : "DB_ACCOUNT_UNVERIFIED";
                auditRefusal(actorId, systemId, id, pre.tableName(), code, tokensOf(code, refusal.get()));
                throw ConfigApiException.conflict(refusal.get());
            }
        }
        try {
            return transactions.execute(status -> applyDecision(id, decision, actorId, pre.beforeStatus()));
        } catch (ConfigApiException refused) {
            // the row changed between the reads and the decision (a real approval only): durable, own transaction
            if (pre.probe() != null && "TARGET_CHANGED".equals(refused.body().get("refusal"))) {
                auditRefusal(actorId, systemId, id, pre.tableName(), "TARGET_CHANGED", List.of());
            }
            throw refused;
        }
    }

    /** The privilege tokens of a TOO_WIDE text, each checked against the fixed vocabulary - no free text gets through. */
    private static List<String> tokensOf(String code, String text) {
        if (!code.equals("DB_ACCOUNT_TOO_WIDE")) {
            return List.of();
        }
        Matcher listed = RIGHTS_LIST.matcher(text);
        if (!listed.find()) {
            return List.of();
        }
        return Arrays.stream(listed.group(1).split(",")).map(String::strip)
                .filter(t -> PRIVILEGE_TOKENS.contains(t) || PREDEFINED_ROLE.matcher(t).matches()).toList();
    }

    /**
     * L19 (Karan ruling 2026-10-23): one {@code DB_TABLE_APPROVAL_REFUSED} event in its OWN short transaction, so it is
     * committed although the refusal is thrown right after; throttled per (row, code) per hour; a failing write is
     * logged (class name only) and never turns the 409 into a 500. Closed code, table identifier and vocabulary tokens
     * only - never a host, account, database name, SQL or driver text.
     */
    private void auditRefusal(UUID actorId, String systemId, String rowId, String table, String code,
            List<String> tokens) {
        String key = rowId + "|" + code;
        Instant admittedAt = admitRefusal(key);
        if (admittedAt == null) {
            return;
        }
        try {
            UUID tenantId = tenant.get().orElseThrow(() -> new IllegalStateException("no tenant bound"));
            String after = tokens.isEmpty()
                    ? ConfigAuditActions.state("status", "PENDING_APPROVAL", "refusal", code, "table", table)
                    : ConfigAuditActions.state("status", "PENDING_APPROVAL", "refusal", code, "table", table,
                            "tokens", tokens);
            AuditEvent event = ConfigAuditActions.event(ConfigAuditActions.DB_TABLE_APPROVAL_REFUSED, tenantId, actorId,
                    List.of(ConfigAuditActions.target("system", systemId),
                            ConfigAuditActions.target("db_table_approval", rowId)),
                    ConfigAuditActions.state("status", "PENDING_APPROVAL"), after);
            auditTransactions.executeWithoutResult(status -> events.publishEvent(event));
        } catch (RuntimeException failed) {
            // F1: the slot is spent only by a write that succeeded - a failed write must not silence the next refusal
            refusalsWritten.remove(key, admittedAt);
            LOG.warn("event=db.table.refusal_audit_failed code={} cause={}", code, failed.getClass().getSimpleName());
        }
    }

    /** The slot's stamp when this refusal is admitted (the caller releases it if the write fails), else null. */
    private Instant admitRefusal(String key) {
        Instant now = clock.instant();
        if (refusalsWritten.size() > THROTTLE_SWEEP_AT) {
            refusalsWritten.values().removeIf(at -> at.plus(REFUSAL_WINDOW).isBefore(now));
        }
        Instant[] admitted = {null};
        refusalsWritten.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(REFUSAL_WINDOW))) {
                admitted[0] = now;
                return now;
            }
            return last;
        });
        return admitted[0];
    }

    private record Probe(UUID systemId, String settings, RuntimeException failure) {
    }

    private record Preflight(String beforeStatus, String tableName, Probe probe) {
    }

    private Preflight preflight(String systemId, String id, String decision) {
        requireSystem(systemId, false);
        if (!SystemAdminService.isUuid(id)) {
            throw ConfigApiException.notFound();
        }
        ApprovedTableRow before = tables.find(id).filter(row -> row.systemId().equals(systemId))
                .orElseThrow(ConfigApiException::notFound);
        boolean realApproval = decision.equals("APPROVE") && before.status().equals("PENDING_APPROVAL");
        return new Preflight(before.status(), before.tableName(), realApproval ? probeInput(systemId, before.tableName()) : null);
    }

    /** The system's settings with the table set; a read failure is carried (not thrown) so it refuses fail-closed. */
    private Probe probeInput(String systemId, String table) {
        try {
            SystemDetail system = systems.findSystem(systemId).orElseThrow(ConfigApiException::notFound);
            JsonNode parsed = system.settings() == null ? null : JSON.readTree(system.settings());
            ObjectNode settings = parsed != null && parsed.isObject() ? ((ObjectNode) parsed).deepCopy()
                    : JSON.createObjectNode();
            settings.put("table", table);
            return new Probe(UUID.fromString(systemId), settings.toString(), null);
        } catch (ConfigApiException notFound) {
            throw notFound;
        } catch (RuntimeException unknown) {
            return new Probe(null, null, unknown);
        }
    }

    private Map<String, Object> applyDecision(String id, String decision, UUID actorId, String beforeStatus) {
        String result = tables.decide(id, actorId.toString(), decision);
        switch (result) {
            case "APPROVED", "REVOKED" -> {
            }
            case "NOT_FOUND" -> throw ConfigApiException.notFound();
            case "REFUSED_TARGET_CHANGED" -> throw ConfigApiException.refusal(409, "TARGET_CHANGED");
            case "REFUSED_NO_ACTOR", "REFUSED_UNKNOWN_DECISION" -> throw new IllegalStateException("the approval function refused: " + result);
            default -> throw ConfigApiException.refusal(409,
                    decision.equals("APPROVE") ? "NOT_PENDING_APPROVAL" : "NOT_APPROVED");
        }
        ApprovedTableRow after = tables.find(id)
                .orElseThrow(() -> new IllegalStateException("the decided approved table vanished"));
        boolean approved = result.equals("APPROVED");
        audit(approved ? ConfigAuditActions.DB_TABLE_APPROVED : ConfigAuditActions.DB_TABLE_REVOKED, actorId, after,
                beforeStatus, approved ? actorId.toString().equals(after.requestedBy()) : null);
        return view(after);
    }

    /** T178a: fail closed - a wide account, or any failure to learn the width, refuses the approval (409). Runs outside any transaction. */
    private Optional<String> widthRefusal(Probe probe) {
        Optional<String> refusal;
        try {
            if (probe.failure() != null) {
                throw probe.failure();
            }
            refusal = width.refusalFor(probe.systemId(), probe.settings());
        } catch (RuntimeException unknown) {
            refusal = Optional.of("DB_ACCOUNT_UNVERIFIED: the privilege probe could not run ("
                    + unknown.getClass().getSimpleName() + "); an approval is refused when the account cannot be checked");
        }
        return refusal;
    }

    private void requireSystem(String systemId, boolean mustBeDb) {
        if (!SystemAdminService.isUuid(systemId)) {
            throw ConfigApiException.notFound();
        }
        SystemDetail system = systems.findSystem(systemId).orElseThrow(ConfigApiException::notFound);
        if (mustBeDb && !"DB".equals(system.type())) {
            throw ConfigApiException.validation("approved tables belong to a DB system; this system is "
                    + (system.type() == null ? "untyped" : system.type()));
        }
    }

    private void audit(String action, UUID actorId, ApprovedTableRow row, String beforeStatus) {
        audit(action, actorId, row, beforeStatus, null);
    }

    /** {@code selfApproved} (approval only; null = not part of the event): the approver is the requester. */
    private void audit(String action, UUID actorId, ApprovedTableRow row, String beforeStatus, Boolean selfApproved) {
        UUID tenantId = tenant.get().orElseThrow(() -> new IllegalStateException("no tenant bound"));
        events.publishEvent(ConfigAuditActions.event(action, tenantId, actorId,
                List.of(ConfigAuditActions.target("system", row.systemId()),
                        ConfigAuditActions.target("db_table_approval", row.id())),
                beforeStatus == null ? null : ConfigAuditActions.state("status", beforeStatus),
                selfApproved == null ? ConfigAuditActions.state("status", row.status(), "table", row.tableName())
                        : ConfigAuditActions.state("status", row.status(), "table", row.tableName(),
                                "selfApproved", selfApproved)));
    }

    private static Map<String, Object> view(ApprovedTableRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("systemId", row.systemId());
        body.put("table", row.tableName());
        body.put("status", row.status());
        body.put("requestedBy", row.requestedBy());
        body.put("requestedAt", row.requestedAt() == null ? null : row.requestedAt().toString());
        body.put("decidedBy", row.decidedBy());
        body.put("decidedAt", row.decidedAt() == null ? null : row.decidedAt().toString());
        body.put("targetCurrent", row.targetCurrent());
        return body;
    }
}
