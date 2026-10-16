package io.integrationplatform.routing.templates;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T-116 wiring: the publish listener that instantiates an interface's route instances from its configuration rows. */
@Configuration(proxyBeanMethods = false)
class RouteTemplatesConfiguration {

    @Bean
    InterfacePublishedRouteListener interfacePublishedRouteListener(ObjectProvider<RouteInstanceFactory> factories) {
        return new InterfacePublishedRouteListener(factories);
    }
}
