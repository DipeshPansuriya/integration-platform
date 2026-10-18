-- row278 M2: V2_2_1220 only ENABLEd row level security on the audit ledger, which exempts the table owner.
-- FORCE puts every role, the owner included, under audit_event_tenant_isolation.
ALTER TABLE public.audit_event FORCE ROW LEVEL SECURITY;
