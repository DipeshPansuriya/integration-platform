package io.integrationplatform.e2e.release1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Row 323 T139/T140: the T-items a scenario (method) or a whole class needs on main; see {@link Release1Pending}. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(WaitsOnCondition.class)
public @interface WaitsOn {

    String[] value();
}
