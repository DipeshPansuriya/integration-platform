package io.integrationplatform.config.flow.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.flow.application.FlowBundle.Parsed;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotMapping;
import io.integrationplatform.config.flow.application.FlowBundle.SnapshotRoute;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.VersionInfo;
import io.integrationplatform.config.internal.persistence.JdbcFlowRouteNames;
import io.integrationplatform.config.internal.persistence.JdbcFlowRouteNames.EndpointRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * T81 / B9: the routes listing the flow designer navigates by. It reads one version's {@link FlowBundle} snapshot -
 * the flow's current (latest: the draft, or the newest version) by default, or the version named by id - so the
 * listing is exactly the bundle that version carries, in its order (sequence, then id). Each route is named by its
 * target endpoint and carries the mapping id the designer opens. Every read is tenant-scoped: an unknown flow,
 * another tenant's flow or a version that is not the flow's answers 404.
 */
public class FlowRoutesQuery {

    private final JdbcFlowRepository flows;
    private final JdbcFlowRouteNames names;

    public FlowRoutesQuery(JdbcFlowRepository flows, JdbcFlowRouteNames names) {
        this.flows = Objects.requireNonNull(flows, "flows");
        this.names = Objects.requireNonNull(names, "names");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> routes(String flowId, String versionId) {
        // A malformed id names no visible row - same answer as an unknown one, and no cast error from the database.
        if (!FlowAdminService.isCanonicalUuid(flowId)
                || (versionId != null && !FlowAdminService.isCanonicalUuid(versionId))) {
            throw ConfigApiException.notFound();
        }
        flows.findInterface(flowId).orElseThrow(ConfigApiException::notFound);
        VersionInfo version = versionId == null
                ? flows.findLatestVersion(flowId)
                        .orElseThrow(() -> new IllegalStateException("a flow always has at least one version"))
                : flows.findVersionOfInterface(flowId, versionId).orElseThrow(ConfigApiException::notFound);
        Parsed bundle = FlowBundle.parse(version.snapshotJson());

        Set<String> endpointIds = new LinkedHashSet<>();
        bundle.routes().forEach(route -> endpointIds.add(route.targetEndpointId()));
        Map<String, EndpointRow> endpoints = names.findEndpoints(endpointIds);
        Map<String, String> mappingByRoute = new LinkedHashMap<>();
        for (SnapshotMapping mapping : bundle.mappings()) {
            mappingByRoute.putIfAbsent(mapping.routeId(), mapping.id());
        }

        List<Map<String, Object>> routes = new ArrayList<>();
        for (SnapshotRoute route : bundle.routes()) {
            EndpointRow endpoint = endpoints.get(route.targetEndpointId());
            routes.add(ConfigApiException.body(
                    "id", route.id(),
                    "name", endpoint == null ? null : endpoint.name(),
                    "sequence", route.sequence(),
                    "isPrimary", route.isPrimary(),
                    "status", route.status(),
                    "condition", route.condition(),
                    "targetEndpointId", route.targetEndpointId(),
                    "targetEndpointCode", endpoint == null ? null : endpoint.code(),
                    "targetSystem", endpoint == null ? null : endpoint.systemCode(),
                    "mappingId", mappingByRoute.get(route.id()),
                    "callbackMode", route.callbackMode(),
                    "callbackUrl", route.callbackUrl()));
        }
        return ConfigApiException.body(
                "flowId", flowId,
                "versionId", version.id(),
                "version", version.version(),
                "versionStatus", version.status(),
                "routes", routes);
    }
}
