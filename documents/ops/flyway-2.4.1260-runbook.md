# Runbook: Flyway 2.4.1260 collision (commit 4f92966)

## What happened

Two `platform-config` migrations both claimed version 2.4.1260:

| File | History row it writes |
|---|---|
| `V2_4_1260__kafka_topic_settings.sql` (kept 2.4.1260) | `2.4.1260`, `kafka topic settings` |
| `V2_4_1260__force_rls_route_mapping_system_target.sql` (renamed to `V2_4_1261` in 4f92966, content unchanged) | was `2.4.1260`, `force rls route mapping system target`; now `2.4.1261` |

A fresh database is not affected: it applies kafka 2.4.1260, then force-RLS 2.4.1261.

## Who can be affected

Only a database that applied the OLD force-RLS file as 2.4.1260 and nothing else under that number. On `main` that
was the window **2026-10-21 12:22 IST (merge da69e9c) to 14:29 IST (merge 3ec65bb, which added the kafka file and made
every tree fail with a duplicate-version error until 4f92966, 21:41 IST)**, plus any branch cut from that window.

| Environment | Can hold the old row? |
|---|---|
| Testcontainers databases (every `-Pcontainer` suite) | No - created empty per run |
| A developer's local compose Postgres (`compose/compose.yaml`, named volume `postgres-data`) that ran the app in the window | **Yes** - the volume survives restarts |
| The local showcase database (Thu 2026-10-22) | Yes, if it was migrated in the window and not recreated |
| Any hosted dev/test/production database | None known: hosting is deferred (roadmap B10); check anyway before the first deploy |

A database that applied only the kafka file as 2.4.1260 (any build before 12:22 on 21 Oct that had E16-T0) is fine:
it just applies 2.4.1261.

## Symptom

The new build refuses to start (Flyway `validate` runs before `migrate`):

```
Migration checksum mismatch for migration version 2.4.1260
-> Applied to database : -1836887444
-> Resolved locally    : -1656716702
```

(Exact text captured by `Flyway1260RenameUpgradeContainerTest`; see the hand-back report.)

## Fix (once per affected database)

1. Check: `SELECT installed_rank, version, description FROM public.flyway_schema_history WHERE version = '2.4.1260';`
   If the description is `force rls route mapping system target`, continue. If it is `kafka topic settings`, stop: nothing to do.
2. Run `documents/ops/flyway-2.4.1260-repair.sql` as the migration owner (`DB_MIGRATION_USERNAME`). It deletes only that
   one history row and reports `DELETE 1`; on any other database it reports `DELETE 0`. Safe to run twice.
3. Start the new build. Flyway applies 2.4.1260 (kafka topic settings) then 2.4.1261 (force RLS, idempotent).
4. If the database already holds a version above 2.4.1260 (for example 2.4.1300) step 3 fails with "Detected resolved
   migration not applied to database: 2.4.1260". Start that one run with `SPRING_FLYWAY_OUT_OF_ORDER=true`, then remove the setting.
5. Simplest alternative for a disposable local database: `docker compose down -v` and start again.

## Do not

- Do not run `flyway repair` (or `Flyway.repair()`). It relabels the old row as `kafka topic settings`, validate turns
  green and the `kafka_topic_setting` table is never created (proved by the test `aBlindFlywayRepairIsTheWrongFix...`).
- Do not edit the applied or renamed migration files again.
