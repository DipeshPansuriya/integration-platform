-- K1 (T146, Dipesh m-263aac): FORCE ROW LEVEL SECURITY on the four K1 tables so the table owner,
-- including any BYPASSRLS-granted role, is also bound by tenant isolation (matching audit_event).
ALTER TABLE public.route FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mapping FORCE ROW LEVEL SECURITY;
ALTER TABLE public.system FORCE ROW LEVEL SECURITY;
ALTER TABLE public.target_endpoint FORCE ROW LEVEL SECURITY;
