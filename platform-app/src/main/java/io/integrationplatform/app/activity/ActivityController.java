package io.integrationplatform.app.activity;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/** RED stub (row327 T118): signatures only, no mapping yet. */
public class ActivityController {

    public ActivityController(ActivityService service, ConfigActors actors,
            ObjectProvider<TenantDenialRecorder> recorders, ObjectProvider<TenantContext> tenantContexts) {
    }

    public ResponseEntity<ActivityService.ActivityPage> list(String action, LocalDate from, LocalDate to,
            Integer page, Integer size) {
        throw new UnsupportedOperationException("RED");
    }

    public ResponseEntity<?> event(long eventId, HttpServletRequest request) {
        throw new UnsupportedOperationException("RED");
    }
}
