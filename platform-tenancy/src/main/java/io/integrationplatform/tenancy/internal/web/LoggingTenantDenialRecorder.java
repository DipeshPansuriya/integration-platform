package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a denied tenant attempt as one structured WARN event ({@code event=tenant.denied}, channel, method, path,
 * bound tenant) - never a token (R14). The path is the raw request URI, so it can carry attacker-chosen text (a
 * forged id in a PATH_ID record); it is neutralised by {@link #clean} before it is logged. Standing rule (security
 * ruling 2026-10-23, C3): raw request text is never persisted to {@code audit_event}; a later audit writer that
 * replaces this recorder must store a structured, validated form instead. The audit writer of a later phase may
 * replace it.
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

    /**
     * No control (Cc, including NEL), format (Cf: zero-width, bidi, BOM, soft hyphen) or line / paragraph separator
     * (Zl, Zp) character reaches the log (CWE-117, CWE-176), and the length is capped at {@value #MAX_PATH} code
     * points - the same standard as the config API's reason text.
     */
    private static String clean(String value) {
        return value.codePoints().filter(LoggingTenantDenialRecorder::loggable).limit(MAX_PATH)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static boolean loggable(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint) && type != Character.FORMAT
                && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR;
    }
}
