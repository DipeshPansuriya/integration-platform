-- Review A-C1 (2026-10-22 base-URL fix): an OUTBOUND API system saved BEFORE the fix holds settings.baseUrl but its
-- default target_endpoint still carries the create-time placeholder, so every delivery to it is refused until someone
-- re-saves the system. This copies settings.baseUrl into the endpoint ONLY where the endpoint still holds the exact
-- placeholder and the system is OUTBOUND (direction TARGET), type API, with a non-blank text baseUrl in its settings.
-- A customised address, INBOUND, non-API and untyped systems, a missing or blank baseUrl and a near-miss of the
-- placeholder are never touched; a second run finds no placeholder left and changes nothing.
--
-- This migration deliberately runs NO destination check (it is SQL, there is no Java here). That is not a gap in the
-- control: the destination guard (scheme, userinfo, link-local, allow-list) runs at SEND time on every call, and the
-- value copied is the one the save-time guard already accepted when those settings were saved. No audit event is
-- written (a migration has no actor); the heal is recorded by this file and the Flyway history.
--
-- target_endpoint and system have FORCE ROW LEVEL SECURITY since V2_4_1261, which binds the table owner too (unless
-- it is a superuser or holds BYPASSRLS), so the statement runs per tenant with the tenant stamped for this
-- transaction, as V2_4_1330 and V2_4_1340 do; the join is on system_id AND tenant_id.
DO $heal$
DECLARE
    tenant uuid;
BEGIN
    FOR tenant IN SELECT id FROM public.tenant LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        UPDATE public.target_endpoint te SET base_url = pg_catalog.btrim(s.settings ->> 'baseUrl')
            FROM public.system s
            WHERE te.tenant_id = tenant
              AND s.tenant_id = te.tenant_id AND s.id = te.system_id
              AND te.base_url = 'https://endpoint.invalid/'
              AND s.direction = 'TARGET'
              AND pg_catalog.upper(s.type) = 'API'
              AND pg_catalog.jsonb_typeof(s.settings -> 'baseUrl') = 'string'
              AND pg_catalog.btrim(s.settings ->> 'baseUrl') <> '';
    END LOOP;
END
$heal$;
