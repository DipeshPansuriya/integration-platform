-- Operator-level destination allow-list entries, managed at runtime through the console settings page.
-- Supplements (never replaces) the startup allow-list from platform.config.destinations.allowed-hosts;
-- the effective list is the union of the two. Not tenant-scoped: a single shared table owned by the operator.
-- No RLS: the admin API endpoint is gated on TENANT_ADMIN by the application's role policy.

CREATE TABLE IF NOT EXISTS public.operator_allowed_host (
    id          uuid         DEFAULT gen_random_uuid() PRIMARY KEY,
    pattern     text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT  operator_allowed_host_pattern_key UNIQUE (pattern),
    CONSTRAINT  operator_allowed_host_pattern_check CHECK (length(trim(pattern)) > 0)
);

GRANT SELECT ON public.operator_allowed_host TO ipaas_app;
GRANT INSERT, DELETE ON public.operator_allowed_host TO ipaas_app;
