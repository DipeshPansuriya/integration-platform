GRANT INSERT ON public.audit_event TO ipaas_app;
GRANT UPDATE (relayed_at) ON public.audit_outbox TO ipaas_app;
DROP FUNCTION IF EXISTS public.audit_relay_move(bigint);
