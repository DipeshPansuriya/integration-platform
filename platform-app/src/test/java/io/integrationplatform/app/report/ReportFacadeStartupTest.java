package io.integrationplatform.app.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReportFacadeStartupTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ReportConfiguration.class, ReportFacadeController.class,
                    ReportFacadeEndpointRoles.class);

    @Test
    void applicationStartsAndWiresFacadeControllerAndRoles() {
        context.run(app -> {
            assertThat(app).hasSingleBean(ReportFacade.class);
            assertThat(app).hasSingleBean(ReportFacadeController.class);
            assertThat(app).hasSingleBean(ReportFacadeEndpointRoles.class);
        });
    }
}
