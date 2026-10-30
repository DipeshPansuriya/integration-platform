-- Phase 2 of IB08 dual-mode callback: per-route callback configuration.
-- callback_mode: NULL (default, no callback), 'IMMEDIATE' (Kafka event-driven), 'SCHEDULED' (timer-based).
-- callback_url: the URL to POST the captured response back to (e.g., Galaxy's callback endpoint).

ALTER TABLE public.route ADD COLUMN IF NOT EXISTS callback_mode text;
ALTER TABLE public.route ADD COLUMN IF NOT EXISTS callback_url  text;

COMMENT ON COLUMN public.route.callback_mode IS
    'IB08: NULL = no callback (default, fire-and-forget); IMMEDIATE = event-driven via Kafka (~3-5s); '
    'SCHEDULED = timer-based via db-scheduler (~10-15s). Configured per route in the flow designer.';

COMMENT ON COLUMN public.route.callback_url IS
    'IB08: the URL to POST the target response back to when callback_mode is set. '
    'Typically the originating system''s callback endpoint (e.g., Galaxy POST /api/integration/callback).';
