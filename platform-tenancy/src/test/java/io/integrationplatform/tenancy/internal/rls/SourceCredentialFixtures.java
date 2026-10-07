package io.integrationplatform.tenancy.internal.rls;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_C;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * E4 lane C test data (T-098): one source system per tenant of {@link RlsTestDatabase} and their machine credentials,
 * written by the owner (which bypasses row-level security, as every seed of the fixture does). Idempotent, so every
 * test class may call {@link #seed()}; tests that change a credential insert their own with {@link #insertCredential}.
 */
public final class SourceCredentialFixtures {

    public static final UUID SYSTEM_A = UUID.fromString("5a000000-0000-4000-8000-00000000000a");
    public static final UUID SYSTEM_B = UUID.fromString("5b000000-0000-4000-8000-00000000000b");
    /** The source system of the SUSPENDED tenant C. */
    public static final UUID SYSTEM_C = UUID.fromString("5c000000-0000-4000-8000-00000000000c");

    public static final String CUSTOMER_A = "CUSTOMER-A";
    public static final String CUSTOMER_B = "CUSTOMER-B";

    /** ACTIVE credential of tenant A's source system, customer A. */
    public static final UUID CRED_A = UUID.fromString("c0000000-0000-4000-8000-0000000000a1");
    public static final String CLIENT_A = "source-a";
    /** PENDING credential of tenant A (creation not finished at the identity provider). */
    public static final UUID CRED_A_PENDING = UUID.fromString("c0000000-0000-4000-8000-0000000000a2");
    public static final String CLIENT_A_PENDING = "source-a-pending";
    /** REVOKED credential of tenant A. */
    public static final UUID CRED_A_REVOKED = UUID.fromString("c0000000-0000-4000-8000-0000000000a3");
    public static final String CLIENT_A_REVOKED = "source-a-revoked";
    /** ACTIVE credential of tenant B's source system, customer B. */
    public static final UUID CRED_B = UUID.fromString("c0000000-0000-4000-8000-0000000000b1");
    public static final String CLIENT_B = "source-b";
    /** ACTIVE credential of the SUSPENDED tenant C. */
    public static final UUID CRED_C = UUID.fromString("c0000000-0000-4000-8000-0000000000c1");
    public static final String CLIENT_C = "source-c";

    private SourceCredentialFixtures() {
    }

    /** Seeds the source systems and the five credentials above once per database (idempotent). */
    public static synchronized void seed() throws SQLException {
        RlsTestDatabase db = RlsTestDatabase.get();
        try (Connection owner = db.ownerConnection(); PreparedStatement system = owner.prepareStatement(
                "insert into public.system (id, tenant_id, code) values (?::uuid, ?::uuid, ?)"
                        + " on conflict (id) do nothing")) {
            addSystem(system, SYSTEM_A, TENANT_A, "erp-a");
            addSystem(system, SYSTEM_B, TENANT_B, "erp-b");
            addSystem(system, SYSTEM_C, TENANT_C, "erp-c");
            system.executeBatch();
        }
        insertCredential(CRED_A, ISSUER, TENANT_A, SYSTEM_A, CUSTOMER_A, CLIENT_A, "ACTIVE");
        insertCredential(CRED_A_PENDING, ISSUER, TENANT_A, SYSTEM_A, CUSTOMER_A, CLIENT_A_PENDING, "PENDING");
        insertCredential(CRED_A_REVOKED, ISSUER, TENANT_A, SYSTEM_A, CUSTOMER_A, CLIENT_A_REVOKED, "REVOKED");
        insertCredential(CRED_B, ISSUER, TENANT_B, SYSTEM_B, CUSTOMER_B, CLIENT_B, "ACTIVE");
        insertCredential(CRED_C, ISSUER, TENANT_C, SYSTEM_C, "CUSTOMER-C", CLIENT_C, "ACTIVE");
    }

    /**
     * Inserts one credential as the owner; {@code revoked_at} is set for a REVOKED one. A row with the same id is left
     * as it is.
     */
    public static void insertCredential(UUID id, String issuer, UUID tenant, UUID system, String customer,
            String clientId, String status) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement insert = owner.prepareStatement(
                "insert into public.source_credential (id, tenant_id, source_system_id, customer_id, issuer,"
                        + " client_id, status, revoked_at)"
                        + " values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, case when ? = 'REVOKED' then now() end)"
                        + " on conflict (id) do nothing")) {
            insert.setString(1, id.toString());
            insert.setString(2, tenant.toString());
            insert.setString(3, system.toString());
            insert.setString(4, customer);
            insert.setString(5, issuer);
            insert.setString(6, clientId);
            insert.setString(7, status);
            insert.setString(8, status);
            insert.executeUpdate();
        }
    }

    private static void addSystem(PreparedStatement insert, UUID id, UUID tenant, String code) throws SQLException {
        insert.setString(1, id.toString());
        insert.setString(2, tenant.toString());
        insert.setString(3, code);
        insert.addBatch();
    }
}
