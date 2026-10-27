package io.integrationplatform.app.console;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * Local-development only: serves the built console UI (ui/dist/) as static resources from Spring Boot,
 * so no separate Vite dev server or proxy is needed. Activated by platform.console.static-resources=true.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "platform.console.static-resources", havingValue = "true")
class ConsoleStaticResourcesConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    SecurityFilterChain consoleStaticResourcesFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.pathPattern("/"),
                        PathPatternRequestMatcher.pathPattern("/index.html"),
                        PathPatternRequestMatcher.pathPattern("/assets/**"),
                        PathPatternRequestMatcher.pathPattern("/licences.json"),
                        PathPatternRequestMatcher.pathPattern("/favicon.ico")))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
