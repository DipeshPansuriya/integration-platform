-- Grant UPDATE to the runtime role on the route table.
-- The route table was created in the base schema (owned by ipaas_migrator).
-- ipaas_app needs UPDATE for the callback configuration endpoint (V2_4_1570 columns).
GRANT UPDATE ON public.route TO ipaas_app;
