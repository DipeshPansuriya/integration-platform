package io.integrationplatform.config.credential.readiness;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Status;

/**
 * T-201 (E10-F15): this instance is ready to take work only while the target credential key check is UP. The stage
 * consumers and the delivery retry pass ask it before each pass; a plain BooleanSupplier keeps platform-config free
 * of a dependency on the modules that ask.
 */
public final class InstanceKeyReadiness implements BooleanSupplier {

    private final TargetCredentialKeysHealthIndicator indicator;

    public InstanceKeyReadiness(TargetCredentialKeysHealthIndicator indicator) {
        this.indicator = Objects.requireNonNull(indicator, "indicator");
    }

    @Override
    public boolean getAsBoolean() {
        return Status.UP.equals(indicator.health().getStatus());
    }
}
