-- Row 542 / F1 (Karan, CWE-178): UNIQUE (tenant_id, code) is case-sensitive, so 'ORD-1' and 'ord-1' could both be
-- published in one tenant - a look-alike a source could post to by mistake. Intake lookup stays exact (the code is
-- never rewritten); this only refuses the second spelling. FlowAdminService maps the clash to the named 409.
--
-- Pre-checked read-only before this file was written (2026-10-27, hosted VPS and local compose): case-variant pairs
-- per tenant = 0 on both. The index is created plainly (CONCURRENTLY cannot run inside Flyway's transaction); the
-- table holds configuration rows only, so the lock is brief. Run the same count on any other environment first:
--   select tenant_id, lower(code), count(*) from public.interface group by 1,2 having count(*) > 1;

CREATE UNIQUE INDEX interface_code_ci_unique ON public.interface (tenant_id, lower(code));
