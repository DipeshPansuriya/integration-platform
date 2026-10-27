-- V2_4_1430: Add auth_identity and auth_token_url columns to target_endpoint, and widen the
-- auth_method CHECK to include BEARER_TOKEN. These support the five configurable auth methods
-- (NO_AUTH, API_KEY, BASIC, BEARER_TOKEN, OAUTH2_CLIENT_CREDENTIALS) from the Systems UI.
-- MUTUAL_TLS and DIGEST stay in the CHECK for forward compatibility but have no UI yet.
-- Idempotent: uses IF NOT EXISTS / DROP-then-ADD for the CHECK so the migration is safe on a
-- database that already has these columns (local dev applied them under V2_4_1420 before the
-- version was reassigned to allow_self_approval).

ALTER TABLE public.target_endpoint ADD COLUMN IF NOT EXISTS auth_identity text;
ALTER TABLE public.target_endpoint ADD COLUMN IF NOT EXISTS auth_token_url text;

ALTER TABLE public.target_endpoint DROP CONSTRAINT IF EXISTS target_endpoint_auth_method_check;
ALTER TABLE public.target_endpoint ADD CONSTRAINT target_endpoint_auth_method_check
    CHECK (auth_method IN ('NO_AUTH', 'API_KEY', 'BASIC', 'DIGEST',
                           'BEARER_TOKEN', 'OAUTH2_CLIENT_CREDENTIALS', 'MUTUAL_TLS'));

GRANT UPDATE (auth_identity, auth_token_url) ON public.target_endpoint TO ipaas_app;
