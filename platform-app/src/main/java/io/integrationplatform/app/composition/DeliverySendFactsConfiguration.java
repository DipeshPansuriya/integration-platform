package io.integrationplatform.app.composition;

import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T-180 open item 1 (row 278): the application's {@link TargetSendFacts} for the delivery retry trigger. */
@Configuration(proxyBeanMethods = false)
class DeliverySendFactsConfiguration {

    @Bean
    TargetSendFacts targetSendFacts(TargetEndpointViews targetEndpointViews) {
        return new TargetEndpointViewsSendFacts(targetEndpointViews);
    }
}
