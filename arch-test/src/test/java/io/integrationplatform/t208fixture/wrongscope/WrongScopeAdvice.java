package io.integrationplatform.t208fixture.wrongscope;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Planted violation: scoped to another module's root (so it would replace that module's error contract). */
@RestControllerAdvice(basePackages = "io.integrationplatform.config")
@Order(0)
public class WrongScopeAdvice {
}
