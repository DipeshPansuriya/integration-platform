/**
 * Mapping transformation engine (ADR-006, Accepted 2026-10-12): the JSONata language through Dashjoin's Java port
 * ({@code com.dashjoin:jsonata}), called directly - the module that must never import {@code org.apache.camel}
 * (E7-C8, guarded by lane A's ArchUnit test). The {@link io.integrationplatform.transform.api.TransformPort} and
 * {@link io.integrationplatform.transform.api.LookupSource} seams are E7's wave-1 contract: lane B implements the
 * port, platform-config implements {@code LookupSource}, lane C's gate and lane B3's parity test call the port.
 */
package io.integrationplatform.transform;