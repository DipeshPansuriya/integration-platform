package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * Row 578 (CWE-862 / CWE-918): the forged-tenant matrix part for the operator allow-list API
 * ({@code OperatorAllowedHostController}; OperatorAllowedHostEndpointRoles: every route is an OPERATOR_AGENT
 * operation). The list is one global, operator-owned list - there is no tenant row to leak or to write - so the matrix
 * proves the other half: no human session and no source credential of either tenant reaches a route, with or without
 * a tenant forgery (403 by caller kind, nothing read or written, the one denial recorded). The suite has no
 * operator-agent principal; the agent's own call is covered by OperatorAllowedHostEndpointRolesTest. No rows are
 * seeded.
 */
final class T578OperatorAllowedHostMatrix implements LaneMatrix {

    @Override
    public List<Entry> entries() {
        String hosts = "/api/v1/admin/operator/allowed-hosts";
        String idA = "00000000-0000-4000-8000-0000000005a1";
        String idB = "00000000-0000-4000-8000-0000000005b1";
        String body = "{\"pattern\":\"t578-denied.example.com\"}";
        return List.of(
                new Entry("GET", hosts, false, s -> hosts, null, null, s -> List.of()),
                new Entry("POST", hosts, true, s -> hosts, null, s -> body, s -> List.of(), 201),
                new Entry("DELETE", hosts + "/{id}", false,
                        s -> hosts + "/" + idA, s -> hosts + "/" + idB, null, s -> List.of(), 204));
    }
}
