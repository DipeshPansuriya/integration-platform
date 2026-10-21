package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository;
import io.integrationplatform.config.internal.persistence.JdbcApprovedTableRepository.ApprovedTableRow;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * E16 F3 (design note 2026-10-22, option B; Dipesh D1-D4): the tables a DB target may write to are requested by a
 * configurator and approved by a DIFFERENT tenant approver - never listed by the person who picks the table in the
 * system's settings. A request is a PENDING_APPROVAL row; approve and revoke run in the database's definer function
 * (V2_4_1330), which refuses the requester approving their own request (D4: even in a one-admin tenant). Each
 * completed action publishes one {@code AuditEvent} (system id, row id, table name and status - nothing else) inside
 * its transaction; a refusal rolls that back, so its record is the controller's log line.
 */
public class ApprovedTableService {

    private final JdbcApprovedTableRepository tables;
    private final JdbcSystemRepository systems;
    private final ApplicationEventPublisher events;
    private final Supplier<Optional<UUID>> tenant;

    public ApprovedTableService(JdbcApprovedTableRepository tables, JdbcSystemRepository systems,
            ApplicationEventPublisher events, Supplier<Optional<UUID>> tenant) {
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
     * tenant, 403 REQUESTER_IS_APPROVER, 409 NOT_PENDING_APPROVAL / NOT_APPROVED.
     */
    @Transactional
    public Map<String, Object> decide(String systemId, String id, String decision, UUID actorId) {
        requireSystem(systemId, false);
        if (!SystemAdminService.isUuid(id)) {
            throw ConfigApiException.notFound();
        }
        ApprovedTableRow before = tables.find(id).filter(row -> row.systemId().equals(systemId))
                .orElseThrow(ConfigApiException::notFound);
        String result = tables.decide(id, actorId.toString(), decision);
        switch (result) {
            case "APPROVED", "REVOKED" -> {
            }
            case "NOT_FOUND" -> throw ConfigApiException.notFound();
            case "REFUSED_SELF_APPROVAL" -> throw ConfigApiException.refusal(403, "REQUESTER_IS_APPROVER");
            case "REFUSED_TARGET_CHANGED" -> throw ConfigApiException.refusal(409, "TARGET_CHANGED");
            case "REFUSED_NO_ACTOR", "REFUSED_UNKNOWN_DECISION" -> throw new IllegalStateException("the approval function refused: " + result);
            default -> throw ConfigApiException.refusal(409,
                    decision.equals("APPROVE") ? "NOT_PENDING_APPROVAL" : "NOT_APPROVED");
        }
        ApprovedTableRow after = tables.find(id)
                .orElseThrow(() -> new IllegalStateException("the decided approved table vanished"));
        audit(result.equals("APPROVED") ? ConfigAuditActions.DB_TABLE_APPROVED : ConfigAuditActions.DB_TABLE_REVOKED,
                actorId, after, before.status());
        return view(after);
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
        UUID tenantId = tenant.get().orElseThrow(() -> new IllegalStateException("no tenant bound"));
        events.publishEvent(ConfigAuditActions.event(action, tenantId, actorId,
                List.of(ConfigAuditActions.target("system", row.systemId()),
                        ConfigAuditActions.target("db_table_approval", row.id())),
                beforeStatus == null ? null : ConfigAuditActions.state("status", beforeStatus),
                ConfigAuditActions.state("status", row.status(), "table", row.tableName())));
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
