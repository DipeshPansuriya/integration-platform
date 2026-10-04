package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a denied tenant attempt as one structured WARN event ({@code event=tenant.denied}, channel, method, path,
 * bound tenant) - never the forged value and never a token (R14). The audit writer of a later phase may replace it.
 */
final class LoggingTenantDenialRecorder implements TenantDenialRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingTenantDenialRecorder.class);
    private static final int MAX_PATH = 512;

    @Override
    public void record(DeniedTenantAttempt attempt) {
        String method = clean(attempt.method());
        String path = clean(attempt.path());
        LOG.atWarn()
                .addKeyValue("event", "tenant.denied")
                .addKeyValue("channel", attempt.channel())
                .addKeyValue("method", method)
                .addKeyValue("path", path)
                .addKeyValue("boundTenant", attempt.boundTenantId())
                .log("event=tenant.denied channel={} method={} path={} boundTenant={}",
                        attempt.channel(), method, path, attempt.boundTenantId());
    }

    /** No line breaks or control characters from the request reach the log (log forging), and the length is capped. */
    private static String clean(String value) {
        String flat = value.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > MAX_PATH ? flat.substring(0, MAX_PATH) : flat;
    }
}
