package io.integrationplatform.transform.api;

import io.integrationplatform.transform.internal.DashjoinJsonataTransformPort;

/**
 * The one public way another module obtains the mapping engine (row 278 E7 B2 follow-up, mahesh-architect's finding):
 * the ADR-006 JSONata engine is handed out as a {@link TransformPort} only, so no class outside platform-transform
 * names {@code transform.internal} (arch-test ModuleInternalsRule). The caller owns the wiring and the T-109 budget
 * figure (platform-config's {@code transform.budget-ms}, default 5000); this module stays free of Spring.
 */
public final class TransformEngines {

    private TransformEngines() {
    }

    /** The JSONata engine behind its hard per-transform budget in milliseconds (positive; T-109). */
    public static TransformPort jsonata(long budgetMs) {
        return new DashjoinJsonataTransformPort(budgetMs);
    }
}
