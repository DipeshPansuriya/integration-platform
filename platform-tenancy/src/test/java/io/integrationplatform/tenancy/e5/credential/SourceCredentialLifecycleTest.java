package io.integrationplatform.tenancy.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.internal.credential.SourceCredentialLifecycle;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.IdentityProviderAdminException;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.CredentialStateException;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.Issued;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.RevokeOutcome;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin.Rotated;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

/**
 * E5-F1-S3 (T-103, ADR-007 Decision 3) at port level: the source-credential lifecycle the tenant admin drives.
 *
 * <pre>
 * Scenario: Issue a source credential
 *   Given a tenant admin and a registered source system
 *   When they issue a credential
 *   Then a confidential Keycloak client is created, the secret is shown exactly once, and the action is audited
 * (here: the order - row PENDING while the provider is called, ACTIVE after; the secret only in the answer)
 *
 * &#64;negative
 * Scenario: Keycloak unavailable during issue
 *   ... the action fails cleanly with a clear message, nothing half-created is left
 * (here: no ACTIVE or PENDING row stays; the dead row is REVOKED with idp_disable_pending so the reconciliation
 *  disables a client a timed-out create may have left at the provider)
 * </pre>
 *
 * Rotation = issue a second credential, the old one scheduled to stop after the grace window; revoke keeps E4's
 * revoke-first order (SourceCredentialRevocation). The reconciliation job that calls {@code abandonStalePending} and
 * {@code revokeExpiredRotations} is E14 T-203 - only the methods are proven here.
 */
@ExtendWith(OutputCaptureExtension.class)
class SourceCredentialLifecycleTest {

    private static final String ISSUER = "https://idp.e5.invalid/realms/platform";
    private static final Duration GRACE = Duration.ofDays(7);
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    private final InMemorySourceCredentialStore store = new InMemorySourceCredentialStore(clock);
    private final RecordingIdentityProvider provider = new RecordingIdentityProvider(ISSUER);
    private final UUID system = UUID.randomUUID();

    private SourceCredentialAdmin lifecycle(Optional<IdentityProviderAdmin> identityProvider) {
        return new SourceCredentialLifecycle(store, TransactionOperations.withoutTransaction(), identityProvider,
                GRACE, PENDING_TIMEOUT);
    }

    private SourceCredentialAdmin lifecycle() {
        return lifecycle(Optional.of(provider));
    }

    @Test
    @DisplayName("Issue: the row is PENDING while the provider creates the client, ACTIVE after; the secret is answered once")
    void issueWritesPendingThenCreatesThenActivates() {
        List<String> statusDuringCreate = new ArrayList<>();
        provider.duringCreate = clientId -> statusDuringCreate.add(store.rows.values().stream()
                .filter(row -> row.clientId.equals(clientId)).findFirst().map(row -> row.status).orElse("none"));

        Issued issued = lifecycle().issue(system, "CUSTOMER-1");

        assertThat(statusDuringCreate).containsExactly("PENDING");
        assertThat(provider.created).containsExactly(issued.clientId());
        assertThat(issued.clientId()).isEqualTo("src-" + issued.credentialId());
        assertThat(issued.secret()).isEqualTo(provider.secrets.get(0));
        assertThat(issued.toString()).doesNotContain(issued.secret());
        InMemorySourceCredentialStore.Row row = store.row(issued.credentialId());
        assertThat(row.status).isEqualTo("ACTIVE");
        assertThat(row.issuer).isEqualTo(ISSUER);
        assertThat(row.customerId).isEqualTo("CUSTOMER-1");
        assertThat(row.sourceSystemId).isEqualTo(system);
    }

    @Test
    @Tag("negative")
    @DisplayName("Keycloak unavailable: a clear failure, no ACTIVE or PENDING row, the dead row waits for the client disable")
    void aFailedCreateLeavesNothingHalfCreated(CapturedOutput output) {
        provider.failCreate = true;

        assertThatThrownBy(() -> lifecycle().issue(system, "CUSTOMER-1"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("try again later");

        assertThat(store.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.status).isEqualTo("REVOKED");
            assertThat(row.idpDisablePending).as("a timed-out create may have left a client: disable it").isTrue();
        });
        assertThat(store.findBySourceSystem(system)).noneMatch(c -> c.status().equals("ACTIVE")
                || c.status().equals("PENDING"));
        assertThat(output).contains("event=credential.source_issue_failed");
    }

    @Test
    @Tag("negative")
    @DisplayName("No identity-provider admin configured: issue refuses before writing anything")
    void issueWithoutAnAdminClientWritesNothing() {
        assertThatThrownBy(() -> lifecycle(Optional.empty()).issue(system, "CUSTOMER-1"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("not configured");
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("Rotate = issue a second credential; both stay valid, the old one stops after the 7-day grace window")
    void rotateIssuesASecondCredentialAndSchedulesTheOld() {
        Issued first = lifecycle().issue(system, "CUSTOMER-1");

        Rotated rotated = lifecycle().rotate(first.credentialId());

        assertThat(rotated.replacedCredentialId()).isEqualTo(first.credentialId());
        assertThat(rotated.replacement().credentialId()).isNotEqualTo(first.credentialId());
        assertThat(rotated.replacement().customerId()).isEqualTo("CUSTOMER-1");
        assertThat(rotated.replacement().sourceSystemId()).isEqualTo(system);
        assertThat(rotated.replacement().secret()).isNotEqualTo(first.secret());
        assertThat(rotated.replacedRevokeAfter()).isEqualTo(clock.instant().plus(GRACE));
        assertThat(store.row(first.credentialId()).status).isEqualTo("ACTIVE");
        assertThat(store.row(rotated.replacement().credentialId()).status).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> lifecycle().rotate(first.credentialId()))
                .isInstanceOf(CredentialStateException.class)
                .extracting(e -> ((CredentialStateException) e).reason())
                .isEqualTo(CredentialStateException.Reason.ALREADY_ROTATING);
    }

    @Test
    @Tag("negative")
    @DisplayName("Rotate refuses an unknown or revoked credential; a failed issue leaves the old one untouched")
    void rotateRefusals() {
        assertThatThrownBy(() -> lifecycle().rotate(UUID.randomUUID()))
                .isInstanceOf(CredentialStateException.class)
                .extracting(e -> ((CredentialStateException) e).reason())
                .isEqualTo(CredentialStateException.Reason.NOT_FOUND);

        Issued first = lifecycle().issue(system, "CUSTOMER-1");
        provider.failCreate = true;
        assertThatThrownBy(() -> lifecycle().rotate(first.credentialId()))
                .isInstanceOf(IdentityProviderAdminException.class);
        assertThat(store.row(first.credentialId()).revokeAfter).as("the old credential keeps working").isNull();

        provider.failCreate = false;
        lifecycle().revoke(first.credentialId());
        assertThatThrownBy(() -> lifecycle().rotate(first.credentialId()))
                .isInstanceOf(CredentialStateException.class)
                .extracting(e -> ((CredentialStateException) e).reason())
                .isEqualTo(CredentialStateException.Reason.NOT_ACTIVE);
    }

    @Test
    @DisplayName("Revoke keeps E4's order: row REVOKED first, then the client disabled; a second revoke changes nothing")
    void revokeIsPlatformFirst() {
        Issued issued = lifecycle().issue(system, "CUSTOMER-1");

        assertThat(lifecycle().revoke(issued.credentialId())).isEqualTo(RevokeOutcome.REVOKED);

        assertThat(store.row(issued.credentialId()).status).isEqualTo("REVOKED");
        assertThat(store.row(issued.credentialId()).idpDisablePending).isFalse();
        assertThat(provider.disabled).containsExactly(issued.clientId());
        assertThat(lifecycle().revoke(issued.credentialId())).isEqualTo(RevokeOutcome.ALREADY_REVOKED);
        assertThat(lifecycle().revoke(UUID.randomUUID())).isEqualTo(RevokeOutcome.NOT_FOUND);
        assertThat(provider.disabled).hasSize(1);
    }

    @Test
    @Tag("negative")
    @DisplayName("Revoke with the provider down, or no admin client: effective at once, the disable stays pending")
    void revokeWithTheProviderDownStaysPending() {
        Issued issued = lifecycle().issue(system, "CUSTOMER-1");
        provider.failDisable = true;
        assertThat(lifecycle().revoke(issued.credentialId())).isEqualTo(RevokeOutcome.IDP_DISABLE_PENDING);
        assertThat(store.row(issued.credentialId()).status).isEqualTo("REVOKED");
        assertThat(store.row(issued.credentialId()).idpDisablePending).isTrue();

        provider.failDisable = false;
        Issued second = lifecycle().issue(system, "CUSTOMER-1");
        assertThat(lifecycle(Optional.empty()).revoke(second.credentialId()))
                .isEqualTo(RevokeOutcome.IDP_DISABLE_PENDING);
        assertThat(store.row(second.credentialId()).status).isEqualTo("REVOKED");
        assertThat(store.row(second.credentialId()).idpDisablePending).isTrue();
    }

    @Test
    @DisplayName("For the E14 job: a PENDING row older than 15 minutes and an expired rotation are revoked, disable pending")
    void staleRowsAreHandedToTheReconciliation() {
        Issued old = lifecycle().issue(system, "CUSTOMER-1");
        lifecycle().rotate(old.credentialId());
        store.insertPending(new io.integrationplatform.tenancy.port.SourceCredentialStore.NewCredential(
                UUID.randomUUID(), system, "CUSTOMER-1", ISSUER, "src-crashed"));

        clock.advance(Duration.ofMinutes(16));
        SourceCredentialLifecycle job = (SourceCredentialLifecycle) lifecycle();
        assertThat(job.abandonStalePending()).isEqualTo(1);
        assertThat(job.revokeExpiredRotations()).as("the grace window is not over yet").isZero();

        clock.advance(GRACE);
        assertThat(job.revokeExpiredRotations()).isEqualTo(1);
        assertThat(store.row(old.credentialId()).status).isEqualTo("REVOKED");
        assertThat(store.row(old.credentialId()).idpDisablePending).isTrue();
    }

    @Test
    @DisplayName("Listing answers metadata only - the secret is never stored, so it cannot be listed")
    void listShowsNoSecret() {
        Issued issued = lifecycle().issue(system, "CUSTOMER-1");
        assertThat(lifecycle().list(system)).singleElement()
                .satisfies(c -> assertThat(c.toString()).doesNotContain(issued.secret()));
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
