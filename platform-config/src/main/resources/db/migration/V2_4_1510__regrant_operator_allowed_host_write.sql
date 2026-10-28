-- V2_4_1510: re-grant INSERT and DELETE on operator_allowed_host to the application role so the
-- operator destination allow-list can be managed through the admin API (/api/v1/admin/operator/allowed-hosts).
-- V2_4_1440 revoked these privileges as security finding H-1; this migration deliberately restores
-- them now that the endpoint is operator-scoped (not tenant-scoped).

GRANT INSERT, DELETE ON public.operator_allowed_host TO ipaas_app;
