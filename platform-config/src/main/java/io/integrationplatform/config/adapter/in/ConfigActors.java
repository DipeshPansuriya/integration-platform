package io.integrationplatform.config.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The acting user of the request behind the API seam (ADR-007 Decision 2): the validated token's (issuer, subject)
 * pair goes through the tenancy module's public {@link LoginBootstrap} port - the one trusted path across the
 * pre-tenant boundary - so this module never imports the tenancy internals and never trusts a client-named user.
 *
 * <p>Reads the security context statically: it is a request-thread view, not a bean, so the controllers construct
 * in every composition (lane A's app without the identity chain included).
 */
public final class ConfigActors {

    private final LoginBootstrap loginBootstrap;

    public ConfigActors(LoginBootstrap loginBootstrap) {
        this.loginBootstrap = Objects.requireNonNull(loginBootstrap, "loginBootstrap");
    }

    /** The directory user the bearer resolves to; 403 when the request carries no resolvable human. */
    public LoginBinding requireActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<Jwt> token = authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                ? Optional.of(jwt)
                : Optional.empty();
        return token
                .flatMap(jwt -> loginBootstrap.resolve(
                        jwt.getClaimAsString(JwtClaimNames.ISS), jwt.getSubject()))
                .orElseThrow(() -> ConfigApiException.refusal(403, "NO_BOUND_USER"));
    }
}
