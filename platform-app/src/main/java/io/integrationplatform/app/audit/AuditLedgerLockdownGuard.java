package io.integrationplatform.app.audit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Row 570 (L3): in {@link RelayMode#DEFINER} the application refuses to start while its own database login can write
 * the audit ledger again - INSERT, UPDATE, DELETE or TRUNCATE on {@code audit_event} or one of its partitions, UPDATE (any column), DELETE or
 * TRUNCATE on {@code audit_outbox}, or INSERT on {@code audit_outbox.relayed_at} (a manual re-grant, a restore from an old dump, a half-run rollback). It runs after every
 * singleton (Flyway included), as {@code current_user}; {@code DatabaseRoleGuard} already refuses a superuser or owner
 * login, so these are the application's real privileges. LEGACY mode (the documented rollback) skips the check; so does
 * a database without the ledger table, and a context without a DataSource.
 */
final class AuditLedgerLockdownGuard implements SmartInitializingSingleton {

    static final String REFUSAL = "audit ledger lockdown is open: the application login holds ";

    /** Every write privilege the lockdown took away; names are schema-qualified so the message does not depend on search_path. */
    private static final String OPEN_LEDGER_PRIVILEGES = """
            select p.priv || ' on ' || n.nspname || '.' || c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             cross join (values ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')) p (priv)
             where (c.oid = 'public.audit_event'::regclass
                    or c.oid in (select inhrelid from pg_inherits where inhparent = 'public.audit_event'::regclass))
               and (has_table_privilege(current_user, c.oid, p.priv)
                    or (p.priv = 'UPDATE' and has_any_column_privilege(current_user, c.oid, 'UPDATE')))
            union all
            select p.priv || ' on public.audit_outbox'
              from (values ('UPDATE'), ('DELETE'), ('TRUNCATE')) p (priv)
             where to_regclass('public.audit_outbox') is not null
               and has_table_privilege(current_user, 'public.audit_outbox', p.priv)
            union all
            select 'UPDATE (' || a.attname || ') on public.audit_outbox'
              from pg_attribute a
             where to_regclass('public.audit_outbox') is not null
               and a.attrelid = to_regclass('public.audit_outbox') and a.attnum > 0 and not a.attisdropped
               and has_column_privilege(current_user, a.attrelid, a.attnum, 'UPDATE')
            union all
            select 'INSERT (relayed_at) on public.audit_outbox'
             where to_regclass('public.audit_outbox') is not null
               and has_column_privilege(current_user, 'public.audit_outbox', 'relayed_at', 'INSERT')
            """;

    private final ObjectProvider<DataSource> dataSource;
    private final RelayMode mode;

    AuditLedgerLockdownGuard(ObjectProvider<DataSource> dataSource, RelayMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (mode != RelayMode.DEFINER) {
            return;
        }
        DataSource source = dataSource.getIfUnique();
        if (source == null) {
            return;
        }
        List<String> open = new ArrayList<>();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet exists = statement.executeQuery("select to_regclass('public.audit_event') is not null")) {
                exists.next();
                if (!exists.getBoolean(1)) {
                    return;
                }
            }
            try (ResultSet rows = statement.executeQuery(OPEN_LEDGER_PRIVILEGES)) {
                while (rows.next()) {
                    open.add(rows.getString(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not verify the audit ledger lockdown", e);
        }
        if (!open.isEmpty()) {
            throw new IllegalStateException(REFUSAL + String.join("; ", open)
                    + " (set IPAAS_AUDIT_RELAY_MODE=LEGACY only after the documented rollback SQL)");
        }
    }
}
