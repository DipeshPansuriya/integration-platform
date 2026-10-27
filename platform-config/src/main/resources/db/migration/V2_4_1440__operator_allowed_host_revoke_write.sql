-- V2_4_1440 (row 510, security finding H-1): the editable global destination allow-list was removed again (PRs 36/37,
-- re-added by accident by PR 40, removed once more by this change). The table stays, dormant: V2_4_1410 is applied and
-- is never edited. Nothing in the application reads or writes it, so the application role must not be able to either:
-- a future code regression then fails with "permission denied" instead of widening every tenant's outbound policy.
-- Idempotent (REVOKE of a privilege that is not held is not an error). SELECT is left in place, harmless on a dormant table.

REVOKE INSERT, DELETE ON public.operator_allowed_host FROM ipaas_app;
