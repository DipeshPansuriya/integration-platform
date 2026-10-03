package io.integrationplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The single deployable (ADR-001): wiring only, no business logic. */
@SpringBootApplication
public class IntegrationPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationPlatformApplication.class, args);
    }
}
