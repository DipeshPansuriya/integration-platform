package io.integrationplatform.tenancy.internal.credential;

import io.integrationplatform.tenancy.api.CredentialCleanup;
import java.util.Objects;
import java.util.Optional;

/**
 * E14 T-203: the {@link CredentialCleanup} pass, reusing what E5 and T-186 built - the lifecycle's two sweeps, then the
 * identity-provider reconciliation (absent where no admin client is configured: the flags then wait for an instance
 * that has one). Sweeps first, so a credential revoked by this pass has its client disabled in the same pass.
 */
final class TenantCredentialCleanup implements CredentialCleanup {

    private final SourceCredentialLifecycle lifecycle;
    private final Optional<IdpDisableReconciliation> reconciliation;

    TenantCredentialCleanup(SourceCredentialLifecycle lifecycle, Optional<IdpDisableReconciliation> reconciliation) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    }

    @Override
    public Result cleanUpBoundTenant() {
        int abandoned = lifecycle.abandonStalePending();
        int expired = lifecycle.revokeExpiredRotations();
        IdpDisableReconciliation.Result disabled = reconciliation.map(IdpDisableReconciliation::reconcile)
                .orElse(new IdpDisableReconciliation.Result(0, 0, 0));
        return new Result(abandoned, expired, disabled.cleared(), disabled.stillPending(), disabled.waiting());
    }
}
