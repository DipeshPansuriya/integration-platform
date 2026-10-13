package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * E7 wave 1 lane B2 (row 278): the role rule of the mapping test endpoint. Running a stored mapping on
 * a sample is the save operation's power (it exercises configuration the editor is about to ship) - the
 * same EDIT_CONFIGURATION chain that reaches the mapping save (E7 plan: no new operation needed, lookup
 * edits and mapping save/test share it). A caller outside the edit-configuration chain answers the
 * platform's standard role refusal.
 */
@Component
public class MappingTestEndpointRoles implements ApiOperationRules {

    static final String MAPPING_TEST = "/api/v1/admin/mappings/{id}/test";

    @Override
    public List<Rule> rules() {
        return List.of(new Rule("POST", MAPPING_TEST, Operation.EDIT_CONFIGURATION));
    }
}