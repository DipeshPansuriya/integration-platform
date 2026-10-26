package io.integrationplatform.t208fixture.noorder;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Planted violation: correctly scoped but no @Order, so its place among the advices is accidental. */
@RestControllerAdvice(basePackages = "io.integrationplatform.t208fixture")
public class NoOrderAdvice {
}
