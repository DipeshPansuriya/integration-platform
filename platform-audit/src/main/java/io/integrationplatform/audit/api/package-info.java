/**
 * E12 seam (arjun-fullstack): the public contract surface for logging and activity tracking - read APIs and
 * value types only. Implementation and persistence stay in {@code platform-app} until the lane fills this
 * module; lanes depend on these types, never on app internals. Contract changes go through arjun-fullstack.
 */
package io.integrationplatform.audit.api;