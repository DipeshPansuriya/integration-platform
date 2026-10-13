package io.integrationplatform.transform.archfixture.compliant;

import io.integrationplatform.domain.delivery.OutcomeClass;

/**
 * Compliant fixture for the transform guards (E7-C8, T66-adjacent): a transform-scoped class that touches neither
 * {@code org.apache.camel} nor any platform-config internals - the engine consumes only its own port types (the
 * ADR-006 JSONata port, lane B1's {@code TransformPort}) and shared domain value types. Test class only - never in
 * the production scan.
 */
public class TransformEngineFixture {

    public OutcomeClass classify(String detail) {
        return OutcomeClass.valueOf(detail);
    }
}