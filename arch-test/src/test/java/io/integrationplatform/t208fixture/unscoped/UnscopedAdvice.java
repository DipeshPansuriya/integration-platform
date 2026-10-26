package io.integrationplatform.t208fixture.unscoped;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Planted violation: no selector, so it would answer for every module's controllers. */
@RestControllerAdvice
@Order(0)
public class UnscopedAdvice {
}
