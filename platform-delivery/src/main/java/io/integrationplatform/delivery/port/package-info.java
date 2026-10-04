/**
 * Ports of platform-delivery that other lanes and modules call or implement (ADR-001 item 5).
 *
 * <p>E2 seam (arjun-fullstack): lane C owns every delivery table and implements
 * {@link io.integrationplatform.delivery.port.DeliveryRetryStore} on them; lane B's retry trigger and outcome recorder
 * (T-090) only call it. A lane that needs a change to a type here reports it to arjun-fullstack instead of editing it.
 */
package io.integrationplatform.delivery.port;
