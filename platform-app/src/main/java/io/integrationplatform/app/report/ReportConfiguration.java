package io.integrationplatform.app.report;

import io.integrationplatform.app.message.MessageTrackingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReportConfiguration {

    @Bean
    MessageTrackingReportDataProvider messageTrackingReportDataProvider(MessageTrackingService service) {
        return new MessageTrackingReportDataProvider(service);
    }

    @Bean
    ReportFacade reportFacade(MessageTrackingReportDataProvider provider) {
        return new DefaultReportFacade(provider);
    }
}