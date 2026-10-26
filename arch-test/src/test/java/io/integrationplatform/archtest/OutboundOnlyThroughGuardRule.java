package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * T-184 "one destination guard for every target type" (E10-F1-S6, ADR-011 Decision 1, S-c): no production class
 * outside {@code io.integrationplatform.config.destination..} may resolve a host name or dial a socket itself.
 * Every outbound adapter goes through {@code DestinationGuard} (check, resolve once, pin) and {@code SocketDialer}
 * (connect to the pinned address), so a new adapter cannot skip the allow-list or the refused ranges.
 */
final class OutboundOnlyThroughGuardRule {

    static final String GUARD_PACKAGE = "io.integrationplatform.config.destination..";

    static final ArchRule OUTBOUND_ONLY_THROUGH_GUARD = noClasses()
            .that().resideInAPackage(CamelOnlyInRoutingRule.ROOT_PACKAGE + "..")
            .and().resideOutsideOfPackages(GUARD_PACKAGE)
            .should().callMethod(InetAddress.class, "getAllByName", String.class)
            .orShould().callMethod(InetAddress.class, "getByName", String.class)
            .orShould().callConstructor(InetSocketAddress.class, String.class, int.class)
            .orShould().callConstructor(Socket.class)
            .orShould().callConstructor(Socket.class, String.class, int.class)
            .orShould().callConstructor(Socket.class, InetAddress.class, int.class)
            .orShould().callMethod(Socket.class, "connect", SocketAddress.class)
            .orShould().callMethod(Socket.class, "connect", SocketAddress.class, int.class)
            .because("T-184: every outbound adapter resolves and dials only through the one destination guard ("
                    + GUARD_PACKAGE + ")");

    private OutboundOnlyThroughGuardRule() {
    }
}
