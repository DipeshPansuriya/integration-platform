-- E9 lane C (T-117 + T-178, E9-C3; ADR-014 section 2, accepted by Dipesh 2026-10-12): the stage-1 pins on the
-- partitioned delivery. The pins are ADD-only (the seam rule); the UNIQUE triple is the idempotency key ADR fix
-- m-817272 B2 chose: a stage-1 delivery is created_at'd with its message's accepted_at (the deterministic day,
-- ADR-014 Decision 1 - the caller cannot defeat the day because the seam port takes the message's instant), so
-- the triple is unique exactly when the (message_id, route_id) pair is, and PostgreSQL still gets a unique index
-- it can enforce across the day partitions (a global unique on the pair alone cannot exist on a range-partitioned
-- parent). There is NO delivery_route table and NO target_endpoint_id column - the pinned target endpoint reuses
-- the existing target_id (V2_3_1:41, the ordering scope column) and contract_version_id exists (V2_3_3_1:15);
-- the port is stage-1's only caller to set message_id (ADR-014's control against a wrong instant).
--
-- The lookup indexes keep the family's patterns (V2_3_1:46-49: tenant-first, the predicate the base appends):
-- the claim/reconciler path reads a delivery by message or route after a crash or a rebalance.

ALTER TABLE public.delivery
    ADD COLUMN message_id         uuid,
    ADD COLUMN route_id           uuid,
    ADD COLUMN mapping_version_id uuid;   -- ADR-013

CREATE UNIQUE INDEX delivery_message_route_unique
    ON public.delivery (message_id, route_id, created_at);

CREATE INDEX delivery_message_idx         ON public.delivery (tenant_id, message_id);
CREATE INDEX delivery_route_idx           ON public.delivery (tenant_id, route_id);
CREATE INDEX delivery_mapping_version_idx ON public.delivery (tenant_id, mapping_version_id);
CREATE INDEX delivery_target_idx          ON public.delivery (tenant_id, target_id);