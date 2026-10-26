package io.integrationplatform.app.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L15 (S5-A1): registers {@link AdminRequestSizeFilter} for the admin API only. The intake endpoints
 * ({@code /api/v1/interfaces/**}) keep their own shape handling and bulk caps and are not matched. The per-field caps
 * (100,000 expression characters, 1,000,000 field-mapping characters) stay where they are, in the mapping service.
 */
@Configuration(proxyBeanMethods = false)
class AdminRequestSizeConfiguration {

    /** Spring Security's filter chain runs at order -100 (Boot's SecurityProperties.DEFAULT_FILTER_ORDER). */
    static final int AFTER_SECURITY_CHAIN = -99;

    @Bean
    FilterRegistrationBean<AdminRequestSizeFilter> adminRequestSizeFilter(
            @Value("${platform.admin-api.max-request-body-bytes:2097152}") long maxBytes) {
        FilterRegistrationBean<AdminRequestSizeFilter> registration =
                new FilterRegistrationBean<>(new AdminRequestSizeFilter(maxBytes));
        registration.addUrlPatterns("/api/v1/admin/*");
        registration.setOrder(AFTER_SECURITY_CHAIN);
        return registration;
    }
}
