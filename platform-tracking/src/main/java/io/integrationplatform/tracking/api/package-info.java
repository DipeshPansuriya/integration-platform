/**
 * E13 seam (arjun-fullstack): the public contract surface for tracking and report reads - timeline, failure and report
 * DTOs and read APIs only. Implementation and persistence stay in {@code platform-app} until the lane fills this
 * module; lanes depend on these types, never on app internals. Contract changes go through arjun-fullstack.
 */
package io.integrationplatform.tracking.api;
