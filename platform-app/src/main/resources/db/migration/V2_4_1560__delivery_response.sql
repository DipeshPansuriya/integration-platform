-- Phase 1 of IB08 dual-mode callback: capture the target's HTTP response body so the callback
-- engine (Phase 3) can send it back to the originating system.

CREATE TABLE IF NOT EXISTS public.delivery_response (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    delivery_id     uuid        NOT NULL,
    status          int         NOT NULL,
    content_type    text,
    body            bytea,
    captured_at     timestamptz NOT NULL DEFAULT now(),
    callback_state  text        NOT NULL DEFAULT 'CAPTURED',
    attempts        int         NOT NULL DEFAULT 0,
    updated_at      timestamptz,
    CONSTRAINT pk_delivery_response PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_response_delivery
    ON public.delivery_response (delivery_id);

CREATE INDEX IF NOT EXISTS idx_delivery_response_callback_state
    ON public.delivery_response (callback_state, captured_at)
    WHERE callback_state IN ('CAPTURED', 'PENDING', 'RETRY');

COMMENT ON TABLE public.delivery_response IS
    'The target''s HTTP response captured after a successful delivery (IB08 callback Phase 1). '
    'The callback engine reads rows in PENDING/RETRY state and sends them to the route''s callback_url.';

COMMENT ON COLUMN public.delivery_response.callback_state IS
    'CAPTURED = stored, no callback configured; PENDING = awaiting first callback attempt; '
    'RETRY = a prior attempt failed transiently; SENT = delivered to the callback URL; '
    'DEAD = all retries exhausted.';

-- Row-level security: every tenant sees only its own rows.
ALTER TABLE public.delivery_response ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.delivery_response FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'delivery_response' AND policyname = 'delivery_response_tenant_isolation'
    ) THEN
        CREATE POLICY delivery_response_tenant_isolation ON public.delivery_response
            USING (tenant_id = current_setting('app.tenant_id')::uuid)
            WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
    END IF;
END $$;
