-- Row 542 / F3 (Karan, typed-code sink trace 2026-10-27): the typed interface code is gated only in FlowAdminService
-- (CODE_PATTERN, PR57), while public.interface.code is plain text. Every sink assumes the charset, so the database
-- now refuses a second writer (an import, a seed, manual SQL) that would bypass the service.
--
-- NOT VALID on purpose: it checks every NEW or UPDATED row at once and scans no existing row, so this migration cannot
-- fail on data already stored. It is NOT validated here. Follow-up, per environment, after a read-only count of 0:
--   select count(*) from public.interface where code !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$';
--   ALTER TABLE public.interface VALIDATE CONSTRAINT interface_code_charset;
-- In PostgreSQL '$' matches only at the true end of the string, so a trailing newline is refused.

ALTER TABLE public.interface
    ADD CONSTRAINT interface_code_charset CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$') NOT VALID;
