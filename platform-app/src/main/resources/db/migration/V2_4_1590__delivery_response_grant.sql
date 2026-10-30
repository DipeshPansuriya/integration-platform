-- Grant DML to the runtime role on delivery_response (missed in V2_4_1560).
-- The table was created by ipaas_migrator; the app role needs SELECT, INSERT, UPDATE
-- for the response capture adapter and the callback engine.
GRANT SELECT, INSERT, UPDATE ON public.delivery_response TO ipaas_app;
