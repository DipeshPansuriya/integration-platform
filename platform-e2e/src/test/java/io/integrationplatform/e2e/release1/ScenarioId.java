package io.integrationplatform.e2e.release1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Row 323 T140: which Release 1 scenario a test method proves; {@link OutcomeRecorder} keys the verdict by it. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScenarioId {

    Release1Scenario value();
}
