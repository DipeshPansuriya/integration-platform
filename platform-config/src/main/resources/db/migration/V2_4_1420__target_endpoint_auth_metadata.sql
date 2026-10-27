-- V2_4_1420: Target endpoint authentication metadata (E10 credentials landing).
--
-- Adds non-secret fields the send-time auth gate needs at call time:
--   auth_identity  - the non-secret half: username (BASIC), client_id (OAUTH2), header name (API_KEY)
--   auth_token_url - the OAuth2 token endpoint URL (OAUTH2_CLIENT_CREDENTIALS only)
--
-- Adds BEARER_TOKEN to the auth_method CHECK (a static token, not a grant flow).
-- The sealed secret stays in target_credential (ADR-007 Decision 7).

ALTER TABLE public.target_endpoint ADD COLUMN auth_identity text;
ALTER TABLE public.target_endpoint ADD COLUMN auth_token_url text;

-- Widen auth_method to include BEARER_TOKEN (inline CHECK name: target_endpoint_auth_method_check)
ALTER TABLE public.target_endpoint DROP CONSTRAINT target_endpoint_auth_method_check;
ALTER TABLE public.target_endpoint ADD CONSTRAINT target_endpoint_auth_method_check
    CHECK (auth_method IN ('NO_AUTH', 'API_KEY', 'BASIC', 'DIGEST', 'BEARER_TOKEN',
                           'OAUTH2_CLIENT_CREDENTIALS', 'MUTUAL_TLS'));

-- Grant UPDATE on the new columns to the app role (additive to V2_2_1's grant)
GRANT UPDATE (auth_identity, auth_token_url) ON public.target_endpoint TO ipaas_app;
