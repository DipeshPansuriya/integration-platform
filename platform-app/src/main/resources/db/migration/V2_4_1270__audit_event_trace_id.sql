-- E12 write side: an activity event carries the trace / correlation id of the request that caused it, so the
-- activity view can be joined to a message timeline and to the logs. Nullable: the config edits that already publish
-- AuditEvent (through the outbox relay) have no trace of their own and stay NULL.
--
-- ADD COLUMN on the partitioned parent reaches every monthly partition; the existing table-level SELECT and INSERT
-- grants and the tenant-isolation policy cover the new column, and UPDATE / DELETE stay revoked (R15).
ALTER TABLE public.audit_event ADD COLUMN trace_id text;
