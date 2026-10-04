/**
 * Data plane delivery - Delivery and attempt persistence, the outcome classifier, the retry policy and the polling
 * re-drive trigger (the delivery-retry job); sends nothing itself (architecture.md section 2, ADR-010). Public
 * surface: this package plus {@code port}; everything else goes under {@code io.integrationplatform.delivery.internal}
 * (ADR-001 item 5).
 */
package io.integrationplatform.delivery;
