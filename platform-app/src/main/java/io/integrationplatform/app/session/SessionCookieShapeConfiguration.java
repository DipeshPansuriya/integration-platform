package io.integrationplatform.app.session;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;

/**
 * B33: wraps the cookie serializer Spring Boot builds from {@code server.servlet.session.cookie.*} (name, HttpOnly,
 * Secure, SameSite) so that only canonical-UUID session ids are looked up. See {@link CanonicalSessionCookieSerializer}.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieShapeConfiguration {

    @Bean
    static BeanPostProcessor canonicalSessionCookies() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof CookieSerializer serializer && !(bean instanceof CanonicalSessionCookieSerializer)
                        ? new CanonicalSessionCookieSerializer(serializer)
                        : bean;
            }
        };
    }
}
