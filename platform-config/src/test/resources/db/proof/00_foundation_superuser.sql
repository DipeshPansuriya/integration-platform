-- Lane B proof harness foundation (T-014, T-018, T-020) - runs as the container's superuser.
-- Mirrors lane C's DeliveryTestDatabase: the migration owner (a CREATEROLE non-superuser, never
-- ipaas_app) and the restricted application role are created outside every migration. Passwords
-- are random per run and live only in the run's process environment (R07).

CREATE ROLE ipaas_migrator LOGIN CREATEROLE PASSWORD :'owner_password';
ALTER DATABASE postgres OWNER TO ipaas_migrator;