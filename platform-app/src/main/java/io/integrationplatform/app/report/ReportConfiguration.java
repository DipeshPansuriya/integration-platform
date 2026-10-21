package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReportConfiguration {

    @Bean
    public ReportDataProvider reportDataProvider(ObjectProvider<MessageTrackingService> service) {
        MessageTrackingService tracking = service.getIfAvailable();
        if (tracking != null) {
            return new MessageTrackingReportDataProvider(tracking);
        }
        return new StubReportDataProvider();
    }

    @Bean
    public ReportFacade reportFacade(ReportDataProvider provider) {
        return new DefaultReportFacade(provider);
    }

    @Bean
    public io.integrationplatform.tracking.api.ReportReadApi reportReadApi(ReportFacade facade) {
        return new JdbcReportReadApi(facade);
    }
}
