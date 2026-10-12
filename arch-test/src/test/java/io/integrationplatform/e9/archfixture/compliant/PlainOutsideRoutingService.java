package io.integrationplatform.e9.archfixture.compliant;

/**
 * COMPLIANT FIXTURE (T-121, E9-F1-S2): a class outside {@code io.integrationplatform.routing} that imports no
 * {@code org.apache.camel} type of any kind - the shape every module outside the routing core must keep. It proves
 * the Camel rule is not a blanket refusal of everything outside platform-routing: compliant classes pass, ONLY the
 * deliberate violation is refused.
 */
final class PlainOutsideRoutingService {

    /** A plain configuration-shaped read: strings in, strings out, no Camel type anywhere. */
    String normalise(String raw) {
        return raw == null ? "" : raw.strip();
    }
}