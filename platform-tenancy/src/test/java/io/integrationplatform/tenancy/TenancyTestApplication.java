package io.integrationplatform.tenancy;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * E1 seam: the test-only Spring Boot configuration for platform-tenancy's own {@code @SpringBootTest} and slice tests
 * (the real application class lives in platform-app, which this module cannot see). It scans
 * {@code io.integrationplatform.tenancy}; every E1 lane's tests use it and no lane adds another.
 */
@SpringBootApplication
public class TenancyTestApplication {
}
