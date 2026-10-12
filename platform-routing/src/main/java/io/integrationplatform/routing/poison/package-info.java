/**
 * The one poison topic producer (E8-F1-S2, T-113): an unreadable record is written once to
 * {@code Topics.POISON}, its partition moves on, and nothing is ever retried from there - poison is
 * never a retry path (E8-C6, E8-C7).
 */
package io.integrationplatform.routing.poison;