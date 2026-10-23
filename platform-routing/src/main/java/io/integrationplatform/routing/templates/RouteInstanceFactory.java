package io.integrationplatform.routing.templates;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.camel.CamelContext;

/**
 * Route instances from configuration, built at runtime, lazily, on every consuming instance (ADR-010 section 3,
 * T-116, E9-C1/E9-C2): publish validates; it never builds (ADR-010 s3 amendment, F16 verdict c) - it reads the
 * interface's active route rows and checks each against {@code allowedSchemes} and the template registry, adding
 * 0 Camel routes and 0 cache entries; routes are built lazily in stage 2, where the consuming instance builds a
 * (target endpoint, version) pair's instance through its target type's {@link RouteTemplate} the first time a
 * delivery needs it; an instance idle for 30 minutes is removed. A local Camel route - rebuilt on demand, never
 * authoritative (ADR-001), 0 deploys and 0 restarts.
 *
 * <p>The {@code allowedSchemes} guard sits before every build and in every publish (E9-C6, T-120 machinery): a row
 * whose endpoint URI scheme is not in the approved list is refused and the publish fails with a clear error naming
 * the scheme - never built, never registered. {@code exec:}, {@code file:} and anything unlisted cannot be reached
 * by configuration.
 *
 * <p>Pinned versions (E9-F1-S1 @negative route side): the instance captures the row's contract and mapping
 * versions when it is BUILT; later configuration changes produce new rows for new messages but never touch
 * instantiated routes, and {@link #ensure} re-uses the existing instance instead of rebuilding.
 */
public final class RouteInstanceFactory {

    /** The idle window of ADR-010 section 3 - a route instance is removed after this much idle time. */
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

    /** Camel route-instance id prefix: deterministic from the configuration route's identity. */
    static final String ROUTE_ID_PREFIX = "e9-route-instance-";

    /** The consumer endpoint prefix every instance answers on; stage 2 dispatches with direct only. */
    static final String CONSUMER_PREFIX = "direct:e9-target-";

    private final RouteConfigReader reader;
    private final CamelContext context;
    private final Map<String, RouteTemplate> templatesByType;
    private final Map<RouteKey, RouteInstance> instancesByKey = new ConcurrentHashMap<>();
    /** The newest instance built per endpoint - what {@link #instanceOf} answers; older versions stay cached. */
    private final Map<UUID, RouteInstance> newestByEndpoint = new ConcurrentHashMap<>();
    private final Set<String> allowedSchemes;
    private final Duration idleTimeout;
    private final Supplier<Instant> clock;

    public RouteInstanceFactory(RouteConfigReader reader, CamelContext context, List<RouteTemplate> templates,
            Set<String> allowedSchemes, Duration idleTimeout, Supplier<Instant> clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.context = Objects.requireNonNull(context, "context");
        this.templatesByType = new HashMap<>();
        for (RouteTemplate template : Objects.requireNonNull(templates, "templates")) {
            this.templatesByType.put(template.targetType(), template);
        }
        this.allowedSchemes = Set.copyOf(Objects.requireNonNull(allowedSchemes, "allowedSchemes"))
                .stream().map(s -> s.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toSet());
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publish validates; it never builds (ADR-010 s3 amendment, F16 verdict c; row 278 T106 F14): every active
     * route row of the interface passes the scheme check and the template-exists check, and nothing else happens -
     * 0 Camel routes added, 0 cache entries written, so a refused row or a failed commit leaves nothing live.
     * Routes are built lazily in stage 2 through {@link #ensure}.
     *
     * @throws RouteBuildRefusedException when a row's endpoint URI scheme is not in allowedSchemes or its target
     *     type has no template - the publish fails with a clear error naming the scheme or type
     */
    public void publishInterface(UUID tenantId, UUID interfaceId) {
        for (RouteConfigRow row : reader.activeRoutes(Objects.requireNonNull(tenantId), Objects.requireNonNull(interfaceId))) {
            approvedTemplate(row);
        }
    }

    /**
     * The lazy creation (ADR-010 section 3): the instance consuming a delivery builds its route when the
     * (target endpoint, version) pair is first needed here; an existing instance is returned and its idle clock
     * reset. Unknown target type or refused scheme fail fast with a clear error.
     */
    public RouteInstance ensure(UUID tenantId, RouteConfigRow row) {
        RouteTemplate template = approvedTemplate(Objects.requireNonNull(row, "row"));
        RouteKey key = row.key(Objects.requireNonNull(tenantId, "tenantId"));
        RouteInstance existing = instancesByKey.get(key);
        if (existing != null) {
            return existing; // lazy re-use: the pinned versions stayed, no rebuild from current configuration
        }
        synchronized (this) {
            existing = instancesByKey.get(key);
            if (existing != null) {
                return existing;
            }
            String routeId = routeIdOf(row);
            if (context.getRoute(routeId) != null) {
                throw RouteBuildRefusedException.routeIdClash("route id '" + routeId
                        + "' is already in the Camel context without a held instance - refusing to rebuild over it");
            }
            org.apache.camel.Processor send = template.build(row);
            registerRouteInstance(routeId, consumerOf(row), send);
            RouteInstance built = new RouteInstance(tenantId, row, clock.get());
            instancesByKey.put(key, built);
            newestByEndpoint.put(row.targetEndpointId(), built);
            return built;
        }
    }

    /**
     * The one validation publish and build share: the row's scheme is in allowedSchemes and its target type has a
     * template. The exception messages are the log detail (including the stage 2 WARN line; the stage 2 dead-letter
     * records no reason) and name only the {@link RefusalText}-bounded scheme or type, the route id and the endpoint
     * id - never the endpoint URI (B22 M1).
     */
    private RouteTemplate approvedTemplate(RouteConfigRow row) {
        String scheme = row.scheme();
        if (!allowedSchemes.contains(scheme)) {
            throw RouteBuildRefusedException.scheme(scheme, "endpoint URI scheme '" + RefusalText.scheme(scheme)
                    + "' is not in allowedSchemes - the route " + row.routeId() + " for endpoint "
                    + row.targetEndpointId() + " is refused: configuration cannot reach an unapproved component");
        }
        RouteTemplate template = templatesByType.get(row.targetType());
        if (template == null) {
            throw RouteBuildRefusedException.targetType(row.targetType(), "no route template for target type '"
                    + RefusalText.targetType(row.targetType()) + "' - the route " + row.routeId() + " for endpoint "
                    + row.targetEndpointId() + " is refused");
        }
        return template;
    }

    /** The newest live instance of an endpoint, if this consuming instance holds one. */
    public Optional<RouteInstance> instanceOf(UUID targetEndpointId) {
        return Optional.ofNullable(newestByEndpoint.get(targetEndpointId));
    }

    /** Marks this instance used now - its own idle window (the send path calls this on each delivery it sends). */
    public void touch(RouteInstance instance, Instant at) {
        Objects.requireNonNull(instance, "instance").touch(Objects.requireNonNull(at, "at"));
    }

    /** Marks an endpoint's newest instance used now. */
    public void touch(UUID targetEndpointId, Instant at) {
        RouteInstance instance = newestByEndpoint.get(targetEndpointId);
        if (instance != null) {
            instance.touch(at);
        }
    }

    /**
     * Removes instances idle for more than the window (30 minutes, ADR-010 section 3): the Camel route is stopped
     * and removed; the next delivery that needs the pair rebuilds it lazily. A sweep - never authoritative; a
     * removed instance is re-created from the row whenever work arrives, so nothing is lost.
     */
    public void evictIdle(Instant now) {
        for (Map.Entry<RouteKey, RouteInstance> entry : instancesByKey.entrySet()) {
            RouteInstance instance = entry.getValue();
            if (Duration.between(instance.lastUsedAt(), now).compareTo(idleTimeout) <= 0) {
                continue;
            }
            // T161 L2: under the same lock as ensure(). The route is stopped FIRST and the map entry removed only once
            // it is really gone, so a failed stop leaves the instance held (the next sweep retries) instead of an
            // orphan route that makes every later ensure() dead-letter PERMANENT on the route-id clash.
            synchronized (this) {
                if (Duration.between(instance.lastUsedAt(), now).compareTo(idleTimeout) > 0
                        && stopRoute(instance.camelRouteId())
                        && instancesByKey.remove(entry.getKey(), instance)) {
                    newestByEndpoint.remove(instance.targetEndpointId(), instance); // only if no newer version took over
                }
            }
        }
    }

    /** The direct consumer endpoint a route instance answers on (stage 2 dispatches to it, direct-only). */
    static String consumerOf(RouteConfigRow row) {
        return CONSUMER_PREFIX + row.targetEndpointId() + "-" + row.endpointVersion();
    }

    /** The Camel route id of a row's instance: the configuration route plus the endpoint version (F16). */
    static String routeIdOf(RouteConfigRow row) {
        return ROUTE_ID_PREFIX + row.routeId() + "-" + row.endpointVersion();
    }

    /** Anchors one instance's send at its direct consumer and registers it under its route id in the context. */
    private void registerRouteInstance(String routeId, String consumer, org.apache.camel.Processor send) {
        try {
            context.addRoutes(new org.apache.camel.builder.RouteBuilder() {
                @Override
                public void configure() {
                    from(consumer).id(routeId).process(send);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("the route instance " + routeId + " could not join the running context: "
                    + e.getMessage(), e);
        }
    }

    /** Stops and removes the route; true when it is no longer in the context. Best-effort: a failure is retried by the next sweep. */
    private boolean stopRoute(String routeId) {
        try {
            // V-low: getRoute is inside the try, so one failing instance cannot abort the whole eviction sweep
            org.apache.camel.Route route = context.getRoute(routeId);
            if (route != null && context instanceof org.apache.camel.impl.DefaultCamelContext camel) {
                camel.removeRoute(route); // a runtime-added route is not found by the by-id removal; drop the Route
            } else if (route != null) {
                context.removeRoute(routeId); // stops, then removes; the next delivery rebuilds lazily
            }
        } catch (Exception e) {
            // a route another thread is still using stays held for the next sweep
        }
        try {
            return context.getRoute(routeId) == null;
        } catch (Exception e) {
            return false;
        }
    }
}
