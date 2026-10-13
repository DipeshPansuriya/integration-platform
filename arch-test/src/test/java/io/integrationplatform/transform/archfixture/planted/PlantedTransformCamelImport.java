package io.integrationplatform.transform.archfixture.planted;

import org.apache.camel.CamelContext;

/**
 * Planted violation for "no transform class imports org.apache.camel" (E7-C8; architecture.md:51, row
 * platform-transform "Must not: Use Camel"): the only Camel module is platform-routing (R12/ADR-010 section 3) -
 * transform is the pure JSONata engine. Test class only - never in the production scan.
 */
public class PlantedTransformCamelImport {

    private CamelContext context; // the plant: a routing-module type reaching into the engine module

    public CamelContext context() {
        return context;
    }
}