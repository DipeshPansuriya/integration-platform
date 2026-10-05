package io.integrationplatform.config.e3;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The composed running system of lane A's platform-config suites (the {@code OutboxChaosTestApplication} pattern):
 * the smallest context that serves the real admin API once it exists.
 *
 * <ul>
 *   <li>Scans {@code io.integrationplatform.config} - lane B's application services and lane C's adapter/in
 *       controllers are beans by their own seams the moment they are integrated; no lane's class name is
 *       referenced here, nothing is stubbed to green.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.persistence} - the stamping transaction manager the
 *       config rows' writes must flow through (R11), exactly what the chaos composition scanned for the same
 *       reason. The classes are not on this module's classpath today (the poms are the lead's seam), so the scan
 *       is empty until the composition places them - an empty package scan is not an error, and the suites' named
 *       gates cover the gap meanwhile.</li>
 *   <li>The auto configuration serves everything from the armed poms: Tomcat (web), Hikari (jdbc). Flyway runs at
 *       context start as the migration login the moment the lead arms the pom - until then the fixture's
 *       named gate covers it.</li>
 * </ul>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence"})
class ConfigE3App {
}