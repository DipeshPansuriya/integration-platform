package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.contract.application.ContractAdminService;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.InterfaceRow;
import io.integrationplatform.config.mapping.application.MappingPublishPinCheck;
import io.integrationplatform.config.system.application.SystemAdminService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * T197 R3 (karan, B33 sign-off): a flow or version id in the path that is not a canonical UUID names no row - 404
 * before any SQL. The repository casts the text with {@code ::uuid}; a non-UUID was a PostgreSQL 22P02 and the
 * catch-all 500. One guard, in front of every flow path id.
 */
class FlowAdminServiceIdGuardTest {

    private static final String FLOW = "11111111-1111-1111-1111-111111111111";
    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final JdbcFlowRepository flows = mock(JdbcFlowRepository.class);
    private final FlowAdminService service = new FlowAdminService(flows, mock(SystemAdminService.class),
            mock(ContractAdminService.class), mock(MappingPublishPinCheck.class), event -> { },
            () -> Optional.of(TENANT));

    private static void notFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConfigApiException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", " ", "1-1-1-1-1", "55555555-5555-5555-5555-55555555555g",
            "55555555555555555555555555555555", "11111111-1111-1111-1111-11111111111\u0000",
            "'; drop table interface; --", "11111111-1111-1111-1111-1111111111111"})
    void aFlowIdThatIsNotACanonicalUuidIs404BeforeAnyDatabaseCast(String flowId) {
        var body = JsonMapper.builder().build().readTree("{}");

        notFound(() -> service.read(flowId));
        notFound(() -> service.saveDraft(flowId, body, ACTOR));
        notFound(() -> service.submit(flowId, body, ACTOR));
        notFound(() -> service.approve(flowId, ACTOR));
        notFound(() -> service.publish(flowId, ACTOR));
        notFound(() -> service.publish(flowId));
        notFound(() -> service.refuseVersionEdit(flowId, FLOW));
        verifyNoInteractions(flows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "1-1-1-1-1", "22222222-2222-2222-2222-22222222222g"})
    void aVersionIdThatIsNotACanonicalUuidIs404BeforeTheVersionLookup(String versionId) {
        when(flows.findInterface(FLOW)).thenReturn(Optional.of(new InterfaceRow(FLOW, "F", "F", "PER_KEY", "DRAFT")));

        notFound(() -> service.refuseVersionEdit(FLOW, versionId));
        org.mockito.Mockito.verify(flows, org.mockito.Mockito.never())
                .findVersionOfInterface(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(versionId));
    }

    @Test
    void aCanonicalIdInEitherCaseStillReachesTheRepository() {
        when(flows.findInterface(FLOW.toUpperCase())).thenReturn(Optional.empty());

        notFound(() -> service.read(FLOW.toUpperCase()));
        org.mockito.Mockito.verify(flows).findInterface(FLOW.toUpperCase());
    }
}
