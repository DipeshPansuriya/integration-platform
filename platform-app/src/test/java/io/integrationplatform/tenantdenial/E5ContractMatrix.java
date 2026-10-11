package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane A (amit-developer, T42: T-105, T-106) - the contract import, approval and version endpoints.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 *
 * <p>The entries drive the core config chain's own contract rows (the tenant's source contract and its 1.0 DRAFT):
 * the lane writes NO contract rows of its own, because the core {@code POST /api/v1/admin/contracts/versions} probe
 * pins the tenant's exact contract-version count ("1|@1.0") and any lane row would break it. So the approve entry's
 * legitimate call is the designed refusal of a DRAFT (409 NOT_PENDING_APPROVAL - the approve walk itself is proven in
 * {@code e5.contract.E5ContractVersionsContainerTest}); its PATH_ID forgery still names tenant B's version and must
 * answer 404. The lane fingerprint adds what the core fingerprint (ids and counts only) cannot see: every contract
 * version's status and approver, and every contract's role - a cross-tenant approve or import would change them.
 */
final class E5ContractMatrix implements LaneMatrix {

    private static final String CONTRACTS = "/api/v1/admin/contracts";
    private static final String VERSIONS = CONTRACTS + "/versions";

    @Override
    public List<Entry> entries() {
        return List.of(
                // T-105: import a target contract version from a JSON Schema (201: a new version resource)
                new Entry("POST", VERSIONS + "/import", true,
                        s -> VERSIONS + "/import", null,
                        s -> "{\"system\":\"" + s.a().config().dstCode() + "\",\"role\":\"TARGET_REQUEST\","
                                + "\"name\":\"" + s.a().config().dstCode() + " target contract\","
                                + "\"versionLabel\":\"2.0\",\"format\":\"JSON_SCHEMA\","
                                + "\"document\":{\"type\":\"object\",\"properties\":{\"clientId\":{\"type\":\"string\"}}}}",
                        s -> List.of("2.0", s.a().config().dstCode(), "TARGET_REQUEST"),
                        201,
                        new WriteProbe(s -> "SELECT count(*) || '|@' || coalesce(string_agg(version_label, ','"
                                + " ORDER BY version_label), '-') FROM public.contract_version WHERE tenant_id = '"
                                + s.a().id() + "'", "1|@1.0", "2|@1.0,2.0")),
                // T-105: every contract of the tenant with its versions side by side
                new Entry("GET", CONTRACTS, false,
                        s -> CONTRACTS, null, null,
                        s -> List.of(s.a().config().contractId().toString(), s.a().config().srcCode(),
                                s.a().config().sourceContractVersionId().toString())),
                // T-105: one contract's versions side by side
                new Entry("GET", CONTRACTS + "/{contractId}/versions", false,
                        s -> CONTRACTS + "/" + s.a().config().contractId() + "/versions",
                        s -> CONTRACTS + "/" + s.b().config().contractId() + "/versions",
                        null,
                        s -> List.of(s.a().config().contractId().toString(),
                                s.a().config().sourceContractVersionId().toString(), "1.0")),
                // T-105: non-author approval; the seeded version is a DRAFT, so the legitimate call is refused 409
                new Entry("POST", VERSIONS + "/{versionId}/approve", false,
                        s -> VERSIONS + "/" + s.a().config().sourceContractVersionId() + "/approve",
                        s -> VERSIONS + "/" + s.b().config().sourceContractVersionId() + "/approve",
                        null,
                        s -> List.of(),
                        409,
                        new WriteProbe(s -> "SELECT status || '/' || coalesce(approver_id::text, '-')"
                                + " FROM public.contract_version WHERE id = '"
                                + s.a().config().sourceContractVersionId() + "'", "DRAFT/-", "DRAFT/-")));
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(tenant.config().dstCode() + ":TARGET_REQUEST");
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT"
                + " (SELECT coalesce(string_agg(id || ':' || status || ':' || coalesce(approver_id::text, '-'), ','"
                + "     ORDER BY id), '') FROM public.contract_version WHERE tenant_id = ?::uuid)"
                + " || '#' || (SELECT coalesce(string_agg(id || ':' || role, ',' ORDER BY id), '')"
                + "     FROM public.contract WHERE tenant_id = ?::uuid)")) {
            facts.setString(1, tenantId.toString());
            facts.setString(2, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return "e5-contract:" + rs.getString(1);
            }
        }
    }
}
