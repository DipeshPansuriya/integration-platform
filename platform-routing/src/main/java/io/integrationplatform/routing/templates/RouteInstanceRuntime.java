package io.integrationplatform.routing.templates;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;

/**
 * Small concrete runtime slice for the routing/Camel core (T-116 / T-68 start): owns one started
 * {@link CamelContext}, the {@link RouteInstanceFactory} bound to it and the lazy runtime path that builds
 * route instances from configuration rows on demand. The context is started by {@link #start()} and stopped by
 * {@link #stop()} - the running system the route instances are built on (0 deploys, 0 restarts).
 */
public final class RouteInstanceRuntime {

    private final CamelContext context;
    private final RouteInstanceFactory factory;
    private volatile boolean started;

    public RouteInstanceRuntime(RouteConfigReader reader, List<RouteTemplate> templates, Set<String> allowedSchemes) {
        this(reader, templates, allowedSchemes, Duration.ofMinutes(30), Instant::now);
    }

    public RouteInstanceRuntime(RouteConfigReader reader, List<RouteTemplate> templates, Set<String> allowedSchemes,
            Duration idleTimeout, java.util.function.Supplier<Instant> clock) {
        this.context = new DefaultCamelContext();
        this.factory = new RouteInstanceFactory(Objects.requireNonNull(reader, "reader"), context,
                Objects.requireNonNull(templates, "templates"), Objects.requireNonNull(allowedSchemes, "allowedSchemes"),
                Objects.requireNonNull(idleTimeout, "idleTimeout"), Objects.requireNonNull(clock, "clock"));
    }

    public void start() throws Exception {
        if (started) {
            return;
        }
        context.start();
        started = true;
    }

    public void stop() throws Exception {
        if (!started) {
            return;
        }
        context.stop();
        started = false;
    }

    public CamelContext context() {
        return context;
    }

    public RouteInstanceFactory factory() {
        return factory;
    }

    public boolean isRunning() {
        return started && context.isStarted();
    }
}
