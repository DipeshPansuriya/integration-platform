package io.integrationplatform.e9.archfixture.planted;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * DELIBERATE VIOLATION (T-121, E9-F1-S2 @negative; the R02 red-green pattern TenantFilterRuleTest uses): a class
 * outside {@code io.integrationplatform.routing} importing {@code org.apache.camel}. Only the Camel rule's
 * fixture check may import this class; the production scans of every ArchUnit rule exclude test classes, so it
 * never pollutes a compliant production run.
 *
 * <p>It fails {@link io.integrationplatform.archtest.CamelOnlyInRoutingRule#CAMEL_ONLY_IN_ROUTING} and must fail
 * ONLY against this deliberate violation: the rule passes every module's compliant production classes and the
 * compliant fixture package beside it.
 */
final class PlantedCamelOutsideRouting extends RouteBuilder {

    /** A Camel type reference - the dependency the rule must name. */
    private Exchange lastExchange;

    @Override
    public void configure() {
        from("direct:planted-camel-outside-routing").setBody(simple("${header.name}"));
    }

    Exchange lastExchange() {
        return lastExchange;
    }
}