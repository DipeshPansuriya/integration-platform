package io.integrationplatform.routing.templates;


/**
 * One route template per target type (ADR-010 section 3, T-116): the factory that turns a configuration row
 * into a live route instance in this consuming instance's Camel context. Target type's adapter behaviour (the
 * call itself, its six controls) arrives with the adapters epic (ADR-015, E10) by implementing this interface
 * per type - wave 0 proves only the instantiation contract: from our configuration rows, at runtime.
 *
 * <p>Camel confinement (R12: templates are where Camel lives by design); the row's endpoint URI scheme is
 * guarded by {@link RouteInstanceFactory} BEFORE {@link #build} is ever called - a template never sees an
 * unapproved URI.
 */
public interface RouteTemplate {

    /** The target type this template instantiates ({@link RouteConfigRow#targetType}). */
    String targetType();

    /**
     * Builds this row's route-instance send: the processor the factory anchors at the instance's direct
     * consumer endpoint ({@code direct:e9-target-<endpointId>}, ADR-010 section 3's toD-dispatchable scheme)
     * and registers under the instance's route id in the running context. Called only for a row whose scheme
     * passed the {@code allowedSchemes} guard; the factory holds the build-once bookkeeping (ensure, eviction).
     * The target type's adapter behaviour (the call itself, its six controls) lands here with E10 (ADR-015).
     *
     * @param row the configuration row (its pinned versions are the instance's authority)
     * @return the send behaviour of one route instance
     */
    org.apache.camel.Processor build(RouteConfigRow row);
}