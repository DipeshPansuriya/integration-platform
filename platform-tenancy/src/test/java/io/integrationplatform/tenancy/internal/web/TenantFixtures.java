package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test data of lane B (T-001): two tenants, a user of each, and a fake login bootstrap (lane C owns the real one). */
final class TenantFixtures {

    static final String AUDIENCE = "platform-api";

    static final UUID TENANT_A = UUID.fromString("a0000000-0000-4000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("b0000000-0000-4000-8000-00000000000b");
    static final UUID USER_ALICE = UUID.fromString("a1000000-0000-4000-8000-0000000000a1");
    static final UUID USER_BOB = UUID.fromString("b1000000-0000-4000-8000-0000000000b1");

    /** alice: tenant A; bob: tenant B; carol: a valid token with no active binding. */
    static final String ALICE = "alice-subject";
    static final String BOB = "bob-subject";
    static final String CAROL = "carol-subject";

    static final LoginBinding ALICE_BINDING = new LoginBinding(
            USER_ALICE, "ACTIVE", TENANT_A, "tenant-a", "ACTIVE", ZoneId.of("Europe/Berlin"));
    static final LoginBinding BOB_BINDING = new LoginBinding(
            USER_BOB, "ACTIVE", TENANT_B, "tenant-b", "ACTIVE", ZoneId.of("Asia/Kolkata"));

    private TenantFixtures() {
    }

    /** Resolves only (issuer, subject) pairs of the one configured issuer; remembers every call. */
    static final class FakeLoginBootstrap implements LoginBootstrap {

        private final String issuer;
        private final Map<String, LoginBinding> bySubject = Map.of(ALICE, ALICE_BINDING, BOB, BOB_BINDING);
        final List<String> calls = new CopyOnWriteArrayList<>();

        FakeLoginBootstrap(String issuer) {
            this.issuer = issuer;
        }

        @Override
        public Optional<LoginBinding> resolve(String issuer, String subject) {
            calls.add(issuer + "|" + subject);
            if (!this.issuer.equals(issuer)) {
                return Optional.empty();
            }
            return Optional.ofNullable(bySubject.get(subject));
        }
    }
}
