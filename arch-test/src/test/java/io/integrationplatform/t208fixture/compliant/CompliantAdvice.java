package io.integrationplatform.t208fixture.compliant;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixture: scoped to its own module root, at @Order(0) - the rule must accept it. */
@RestControllerAdvice(basePackages = "io.integrationplatform.t208fixture")
@Order(0)
public class CompliantAdvice {
}
