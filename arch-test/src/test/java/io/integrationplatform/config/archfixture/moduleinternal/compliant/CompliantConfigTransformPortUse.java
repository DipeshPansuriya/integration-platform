package io.integrationplatform.config.archfixture.moduleinternal.compliant;

import io.integrationplatform.transform.api.TransformPort;

/** Compliant fixture: config sees transform only through its api port, handed in from outside. */
public final class CompliantConfigTransformPortUse {

    private final TransformPort transform;

    public CompliantConfigTransformPortUse(TransformPort transform) {
        this.transform = transform;
    }

    public TransformPort transform() {
        return transform;
    }
}
