/**
 * Route templates (E9 lane A1's home package, amit-developer): routes instantiated at publish time from
 * configuration rows on the running system - 0 deploys, 0 restarts - and created lazily on every consuming
 * instance (ADR-010 section 3, T-116). Includes the {@code allowedSchemes} guard applied when a route is built
 * (T-120). Later configuration changes never alter a delivery's pinned versions - only new messages use new
 * versions (E9-F1-S1).
 *
 * <p>E9 seam skeleton (arjun-fullstack) - the implementation arrives with lane A1; other lanes never edit here.
 */
package io.integrationplatform.routing.templates;