package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** T-157 adapter, bare: the contract lane A's ArchUnit rule and lane B's resolver rely on. */
class JdbcLoginBootstrapTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoginBootstrap bootstrap = new JdbcLoginBootstrap(jdbc);

    @Test
    void aMissingOrBlankIssuerOrSubjectResolvesToNothingWithoutAQuery() {
        assertThat(bootstrap.resolve(null, "alice")).isEmpty();
        assertThat(bootstrap.resolve("https://idp.test/realms/platform", null)).isEmpty();
        assertThat(bootstrap.resolve(" ", "alice")).isEmpty();
        assertThat(bootstrap.resolve("https://idp.test/realms/platform", "")).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void isTheDeclaredBoundaryCrossingOfTheLoginBootstrapFunction() {
        assertThat(JdbcLoginBootstrap.class.getAnnotation(TenantBoundaryCrossing.class).value())
                .isEqualTo("ipaas_login_bootstrap");
    }
}
