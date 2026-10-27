package io.integrationplatform.config.system.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.application.ConfigApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L19: the width refusals are 409 {@code {"error": ...}} with no {@code refusal} key; the log gives each its own closed token. */
class ApprovedTableAuditOutcomeTest {

    @Test
    @DisplayName("DB_ACCOUNT_TOO_WIDE and DB_ACCOUNT_UNVERIFIED log their own token, not CONFLICT")
    void widthRefusalsHaveTheirOwnOutcome() {
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.conflict(
                "DB_ACCOUNT_TOO_WIDE: the account 'svc_login' holds more than INSERT"))).isEqualTo("DB_ACCOUNT_TOO_WIDE");
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.conflict(
                "DB_ACCOUNT_UNVERIFIED: the target database did not answer"))).isEqualTo("DB_ACCOUNT_UNVERIFIED");
    }

    @Test
    @DisplayName("Every other outcome is unchanged")
    void otherOutcomesAreUnchanged() {
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.conflict("already requested"))).isEqualTo("CONFLICT");
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.refusal(409, "NOT_PENDING_APPROVAL")))
                .isEqualTo("NOT_PENDING_APPROVAL");
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.validation("bad"))).isEqualTo("INVALID");
        assertThat(ApprovedTableAudit.outcomeOf(ConfigApiException.notFound())).isEqualTo("NOT_FOUND");
    }
}
