/**
 * The public flow surface of the configuration module (T-116): the {@link
 * io.integrationplatform.config.flow.api.InterfacePublished} announcement the routing module instantiates route
 * instances from. The only package of {@code config.flow} other modules use.
 *
 * <p>The refusal contract (row 278 C2, ADR-017): a {@code BEFORE_COMMIT} listener of {@code InterfacePublished}
 * refuses the publish by throwing {@link io.integrationplatform.config.flow.api.InterfacePublishRefused}; the publish
 * rolls back and the configuration API answers 400 with the refusal's reason. The thrower sanitises that reason - no
 * URI, no userinfo, no credential, bounded length.
 */
package io.integrationplatform.config.flow.api;
