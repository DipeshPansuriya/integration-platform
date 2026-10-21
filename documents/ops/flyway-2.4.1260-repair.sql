-- One-time repair for a database that applied the OLD V2_4_1260__force_rls_route_mapping_system_target.sql
-- (platform-config; renamed to V2_4_1261 in commit 4f92966 because kafka_topic_settings also claimed 2.4.1260).
-- Run ONCE as the migration owner (DB_MIGRATION_USERNAME) BEFORE starting the new build. Steps: documents/ops/flyway-2.4.1260-runbook.md.
--
-- It removes only the legacy history row, matched on version + description + checksum, so it is a no-op (0 rows) on a
-- fresh database, on an already repaired one and on a database that holds the kafka 2.4.1260 row. FORCE ROW LEVEL
-- SECURITY set by the old file stays in place; the renamed V2_4_1261 re-applies it harmlessly (ALTER ... FORCE is
-- idempotent) and V2_4_1260__kafka_topic_settings applies for the first time. Never use `flyway repair` for this:
-- it would relabel the row as the kafka migration and the kafka_topic_setting table would never be created.
DELETE FROM public.flyway_schema_history
 WHERE version = '2.4.1260'
   AND description = 'force rls route mapping system target'
   AND checksum = -1836887444;
