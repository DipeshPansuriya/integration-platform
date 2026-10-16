package io.integrationplatform.routing.templates;

import io.integrationplatform.config.flow.api.InterfacePublished;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * T-116 (ADR-010 section 3, E9-C1/E9-C2): the config publish path's {@link InterfacePublished} announcement turns
 * into route instances on the running system - the factory reads the interface's active configuration rows and
 * instantiates each through its target type's template. Runs inside the publish transaction just before it commits:
 * a refused row (unapproved scheme, unknown target type) throws the factory's {@link RouteBuildRefusedException},
 * which rolls the publish back - the publish fails with a clear error (E9-F1-S2 @negative @security). A publish
 * outside a transaction is still delivered.
 *
 * <p>The factory is optional until the composition supplies it (the real {@link RouteConfigReader} adapter and the
 * per-type templates arrive with integration wiring and E10, ADR-015): without one the announcement is a no-op and
 * the consuming instance still builds routes lazily through {@link RouteInstanceFactory#ensure}.
 */
public final class InterfacePublishedRouteListener {

    private final ObjectProvider<RouteInstanceFactory> factories;

    public InterfacePublishedRouteListener(ObjectProvider<RouteInstanceFactory> factories) {
        this.factories = Objects.requireNonNull(factories, "factories");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onInterfacePublished(InterfacePublished published) {
        RouteInstanceFactory factory = factories.getIfAvailable();
        if (factory != null) {
            factory.publishInterface(published.tenantId(), published.interfaceId());
        }
    }
}
