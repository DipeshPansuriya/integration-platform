package io.integrationplatform.e2e.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.e2e.seed.TenantSeedPlan.SeedUser;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Row 327 T157 (Dipesh option 1, memory m-68d957), the pure part of the tenant B seed: SC-07 and SEC-01 need a real
 * second tenant B next to tenant A with exactly one TENANT_ADMIN user, one READ_ONLY user and one client-credentials
 * source credential. Codes and logins are generated per run; no literal password exists anywhere (R07).
 */
class TenantSeedPlanTest {

    private static final String TENANT_A = "demo-tenant";

    @Test
    void tenantBHoldsExactlyATenantAdminAReadOnlyUserAndOneClientCredentialsSourceCredential() {
        TenantSeedPlan plan = TenantSeedPlan.tenantB(TENANT_A);

        assertThat(plan.users()).extracting(SeedUser::role)
                .containsExactly(TenantSeedPlan.TENANT_ADMIN, TenantSeedPlan.READ_ONLY);
        assertThat(plan.roles()).containsExactlyInAnyOrder("TENANT_ADMIN", "READ_ONLY");
        assertThat(plan.credential().grantType()).isEqualTo("client_credentials");
        assertThat(plan.credential().systemCode()).isNotBlank();
        assertThat(plan.credential().customerId()).isNotBlank();
    }

    @Test
    void tenantBsCodeDiffersFromTenantAsAndCarriesTheSeedMarker() {
        TenantSeedPlan plan = TenantSeedPlan.tenantB(TENANT_A);

        assertThat(plan.tenantCode()).isNotEqualTo(TENANT_A).startsWith(TenantSeedPlan.MARKER + "-");
        assertThat(plan.users()).allSatisfy(user -> assertThat(user.username()).startsWith(TenantSeedPlan.MARKER + "-"));
    }

    @Test
    void tenantBsCodeDiffersFromTenantAsEvenWhenTheGeneratorWouldRepeatIt() {
        String drawnFirst = TenantSeedPlan.tenantB(TENANT_A, new Random(7)).tenantCode();

        assertThat(TenantSeedPlan.tenantB(drawnFirst, new Random(7)).tenantCode()).isNotEqualTo(drawnFirst);
    }

    @Test
    void everyRunGetsItsOwnTenantCodeLoginsAndPasswords() {
        TenantSeedPlan first = TenantSeedPlan.tenantB(TENANT_A);
        TenantSeedPlan second = TenantSeedPlan.tenantB(TENANT_A);

        assertThat(second.tenantCode()).isNotEqualTo(first.tenantCode());
        assertThat(second.user(TenantSeedPlan.TENANT_ADMIN).username())
                .isNotEqualTo(first.user(TenantSeedPlan.TENANT_ADMIN).username());
        assertThat(second.user(TenantSeedPlan.READ_ONLY).password())
                .isNotEqualTo(first.user(TenantSeedPlan.READ_ONLY).password());
    }

    @Test
    void passwordsAreGeneratedLongAndDistinctAndNeverPrinted() {
        TenantSeedPlan plan = TenantSeedPlan.tenantB(TENANT_A);
        SeedUser admin = plan.user(TenantSeedPlan.TENANT_ADMIN);
        SeedUser readOnly = plan.user(TenantSeedPlan.READ_ONLY);

        assertThat(admin.password().length()).isGreaterThanOrEqualTo(24);
        assertThat(admin.password()).isNotEqualTo(readOnly.password());
        assertThat(admin.password()).doesNotContain(admin.username());
        assertThat(plan.toString()).doesNotContain(admin.password()).doesNotContain(readOnly.password());
        assertThat(admin.toString()).contains(admin.username()).doesNotContain(admin.password());
    }

    @Test
    void aBlankTenantACodeIsRefused() {
        assertThatThrownBy(() -> TenantSeedPlan.tenantB(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSeedPackageAndMarkerAreTheOnesArchTestsScopeGuardSearchesProductionFor() {
        // arch-test cannot see this module (black box, test only); ModuleScopeGuard.SEED_MARKERS names these strings.
        assertThat(TenantSeedPlan.class.getPackageName()).isEqualTo("io.integrationplatform.e2e.seed");
        assertThat(TenantSeedPlan.MARKER).isEqualTo("e2e-seed");
    }
}
