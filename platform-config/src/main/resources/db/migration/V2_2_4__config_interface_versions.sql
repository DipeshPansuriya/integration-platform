-- E3 lane B, T-018 + T-020: the immutable flow-version snapshot table, its lifecycle rules and the
-- author-never-approver invariant (ADR-011, backlog E3-F2-S1).
--
-- Version tables get NO content rights for the application role - the outbox pattern expanded to a
-- policy: `interface_version` rows are INSERT/SELECT only for ipaas_app, and even the owner's
-- connection is policed by the transition guard trigger below, so no application path can edit a
-- submitted (or any) version - a new draft version row is the only way. Each save is a new row and
-- carries the one canonical serialization of the bundle (interface + routes + mappings) in
-- `snapshot`; the walk draft -> submit -> approve -> publish moves only lifecycle columns.
--
-- Approval is the one write that must never be self-served, so it has its own database path
-- (config_approve_version, SECURITY DEFINER): author-as-approver is refused AND recorded in
-- config_version_refusal, whatever the caller (screen, API or a straight database connection); a
-- direct UPDATE through the app's or the owner's connection meets the same refusal from the
-- approver_not_author CHECK. The refusal record is this task's audit trail.

CREATE TABLE public.interface_version (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 uuid        NOT NULL REFERENCES public.tenant (id),
    interface_id              uuid        NOT NULL,
    version                   integer     NOT NULL CHECK (version > 0),
    status                    text        NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'REJECTED', 'ARCHIVED')),
    business_key_rule         text        NOT NULL,
    snapshot                  jsonb       NOT NULL,
    source_contract_version_id uuid       NOT NULL,
    change_note               text,
    rejection_comment         text,
    author_id                 uuid        NOT NULL,
    approver_id               uuid,
    submitted_at              timestamptz,
    decided_at                timestamptz,
    published_at              timestamptz,
    created_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT approver_not_author CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT interface_version_interface_composite FOREIGN KEY (tenant_id, interface_id)
        REFERENCES public.interface (tenant_id, id),
    CONSTRAINT interface_version_source_contract_composite FOREIGN KEY (tenant_id, source_contract_version_id)
        REFERENCES public.contract_version (tenant_id, id),
    CONSTRAINT interface_version_number_unique UNIQUE (interface_id, version),
    CONSTRAINT interface_version_tenant_id_key UNIQUE (tenant_id, id)
);

-- The refusal record (BR-04's audit trail; written only by the approval function as its owner).
CREATE TABLE public.config_version_refusal (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES public.tenant (id),
    version_id uuid NOT NULL,
    refusal    text NOT NULL CHECK (refusal IN ('AUTHOR_IS_APPROVER', 'NOT_PENDING_APPROVAL')),
    actor_id   uuid,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT config_refusal_version_composite FOREIGN KEY (tenant_id, version_id)
        REFERENCES public.interface_version (tenant_id, id)
);

ALTER TABLE public.interface_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.config_version_refusal ENABLE ROW LEVEL SECURITY;

CREATE POLICY interface_version_tenant_isolation ON public.interface_version
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY config_version_refusal_tenant_isolation ON public.config_version_refusal
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX interface_version_by_interface_idx ON public.interface_version (interface_id, version);

-- The lifecycle guard: applies to every role (the owner runs it too), so immutability is not a
-- grants accident but a schema rule.
CREATE OR REPLACE FUNCTION public.config_version_transition_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.interface_id IS DISTINCT FROM OLD.interface_id
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.business_key_rule IS DISTINCT FROM OLD.business_key_rule
       OR NEW.snapshot IS DISTINCT FROM OLD.snapshot
       OR NEW.source_contract_version_id IS DISTINCT FROM OLD.source_contract_version_id
       OR NEW.author_id IS DISTINCT FROM OLD.author_id THEN
        RAISE EXCEPTION
            'a config version is immutable content; a new draft version is the only way to change it'
            USING ERRCODE = 'P0001';
    END IF;

    IF OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'PENDING_APPROVAL' THEN
        NEW.submitted_at := coalesce(NEW.submitted_at, now());
        RETURN NEW;
    ELSIF OLD.status = 'PENDING_APPROVAL' AND NEW.status = 'APPROVED' THEN
        IF NEW.approver_id IS NULL THEN
            RAISE EXCEPTION 'an approved version names its approver' USING ERRCODE = 'P0001';
        END IF;
        NEW.decided_at := coalesce(NEW.decided_at, now());
        RETURN NEW;
    ELSIF OLD.status = 'PENDING_APPROVAL' AND NEW.status = 'REJECTED' THEN
        IF NEW.rejection_comment IS NULL OR btrim(NEW.rejection_comment) = '' THEN
            RAISE EXCEPTION 'a rejected version carries its rejection comment' USING ERRCODE = 'P0001';
        END IF;
        NEW.decided_at := coalesce(NEW.decided_at, now());
        RETURN NEW;
    ELSIF OLD.status = 'APPROVED' AND NEW.status = 'PUBLISHED' THEN
        NEW.published_at := coalesce(NEW.published_at, now());
        RETURN NEW;
    ELSIF OLD.status = 'PUBLISHED' AND NEW.status = 'ARCHIVED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'status walk refused: % to % is not a lifecycle step (draft, submit, approve, publish)',
        OLD.status, NEW.status
        USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER interface_version_transition_guard
    BEFORE UPDATE ON public.interface_version
    FOR EACH ROW EXECUTE FUNCTION public.config_version_transition_guard();

-- The approval path. SECURITY DEFINER (owned by the migration owner, never superuser) so the write
-- runs the guard and the refusal record atomically; every read and write inside it is scoped to the
-- CALLER'S stamped tenant, never to a caller argument (ADR-007 Decision 6).
CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v public.interface_version%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.interface_version
        WHERE id = p_version_id
          AND tenant_id = current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.author_id = p_approver_id THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'AUTHOR_IS_APPROVER', p_approver_id);
        RETURN 'REFUSED_AUTHOR_IS_APPROVER';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', p_approver_id);
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.interface_version
        SET status = 'APPROVED', approver_id = p_approver_id, decided_at = now()
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

REVOKE ALL ON FUNCTION public.config_approve_version(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.config_approve_version(uuid, uuid) TO ipaas_app;

-- Submit, publish and the draft saves are row writes on lifecycle columns; content is unreachable.
GRANT SELECT, INSERT ON public.interface_version TO ipaas_app;
GRANT UPDATE (status, change_note, rejection_comment, approver_id, submitted_at, decided_at,
              published_at)
    ON public.interface_version TO ipaas_app;

-- The refusal record is read only; even its insert belongs to the approval function (owner path).
GRANT SELECT ON public.config_version_refusal TO ipaas_app;