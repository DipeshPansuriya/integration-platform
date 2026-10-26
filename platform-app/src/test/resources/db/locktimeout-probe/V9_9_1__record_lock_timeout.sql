-- T204 test-only migration (never on the production classpath location db/migration): records the lock_timeout the
-- migration transaction actually ran under, so MigrationLockTimeoutContainerTest can prove the callback's SET LOCAL
-- reached the transaction that runs a migration file.
CREATE TABLE public.lock_timeout_probe AS SELECT current_setting('lock_timeout') AS seen;
