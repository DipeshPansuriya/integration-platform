package io.integrationplatform.routing.compose;

import org.apache.camel.ProducerTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E18 run 2: the route templates of the non-API target types. Each hands the claimed send to the ONE layer-1 route
 * ({@link LayerOneRouteTemplate}), which picks the adapter by the stored {@code system.type}. A template is registered
 * under the same gate as its adapter ({@code KafkaAdapterConfiguration}, {@code DbAdapterConfiguration},
 * {@code FileAdapterConfiguration}), so a gate that is off still leaves the type without a template and the factory
 * refuses it (stage 2 records PERMANENT). MQ's call is part of the pipeline itself (same gate as
 * {@code PipelineCompositionConfiguration}), so its template is too.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class TargetTypeRouteTemplatesConfiguration {

    @Bean
    LayerOneRouteTemplate mqRouteTemplate(ProducerTemplate producerTemplate) {
        return new LayerOneRouteTemplate("MQ", producerTemplate);
    }

    @Bean
    @ConditionalOnProperty("IPASS_KAFKA_TOPIC_TARGET_ENABLED")
    LayerOneRouteTemplate kafkaRouteTemplate(ProducerTemplate producerTemplate) {
        return new LayerOneRouteTemplate("KAFKA", producerTemplate);
    }

    @Bean
    @ConditionalOnProperty("IPASS_DB_TARGET_ENABLED")
    LayerOneRouteTemplate dbRouteTemplate(ProducerTemplate producerTemplate) {
        return new LayerOneRouteTemplate("DB", producerTemplate);
    }

    @Bean
    @ConditionalOnProperty("IPASS_FILE_TARGET_ENABLED")
    LayerOneRouteTemplate fileRouteTemplate(ProducerTemplate producerTemplate) {
        return new LayerOneRouteTemplate("FILE", producerTemplate);
    }
}
