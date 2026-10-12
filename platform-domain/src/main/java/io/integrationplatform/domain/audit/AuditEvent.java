package io.integrationplatform.domain.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * E7 seam (arjun-fullstack, F5): the one audit event every configuration edit publishes - who, what, before, after,
 * when (E7-F3-S1 "The edit is audited", T-163 wave 0; E7-C12). A plain record: platform-domain depends on no Spring
 * (ADR-001), so the caller publishes it with Spring's {@code ApplicationEventPublisher} and E12's append-only writer
 * (T-043) is the subscriber that persists it; until E12 lands nothing is stored. Shape is architecture's
 * (mahesh-architect's documents) - changes go through arjun-fullstack.
 */
public record AuditEvent(UUID tenantId, String who, String action, List<String> targetIds, String before,
        String after, Instant at) {
}