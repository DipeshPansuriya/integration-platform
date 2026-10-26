-- Showcase tenants, directory users and roles (no tenant onboarding API exists: ADR-H-IPASS-01 Decision 3 "Seed").
-- Pattern of ui/scripts/boot-backend.mjs extraTenantSql: tenant row, app_user (issuer + Keycloak subject), role_grant.
-- Run as the bootstrap superuser (break-glass; the FORCE-RLS directory tables reject an ordinary login) by seed-showcase.sh:
--   psql -v issuer=... -v sub_admin_a=... -v sub_cfg_a=... -v sub_appr_a=... -v sub_admin_b=... -f -
-- Idempotent: every INSERT is guarded. Fictional data only; values arrive as psql variables, never in this file.
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO public.tenant (code, status)
SELECT v.code, 'ACTIVE' FROM (VALUES ('showcase-a'), ('showcase-b')) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM public.tenant t WHERE t.code = v.code);

CREATE TEMP TABLE seed_user (tenant_code text, subject text, role text) ON COMMIT DROP;
INSERT INTO seed_user VALUES
  ('showcase-a', :'sub_admin_a', 'TENANT_ADMIN'),
  ('showcase-a', :'sub_cfg_a',   'CONFIGURATOR'),
  ('showcase-a', :'sub_appr_a',  'APPROVER'),
  ('showcase-b', :'sub_admin_b', 'TENANT_ADMIN');

INSERT INTO public.app_user (tenant_id, issuer, subject, status)
SELECT t.id, :'issuer', s.subject, 'ACTIVE'
FROM seed_user s JOIN public.tenant t ON t.code = s.tenant_code
WHERE NOT EXISTS (SELECT 1 FROM public.app_user u WHERE u.issuer = :'issuer' AND u.subject = s.subject);

INSERT INTO public.role_grant (tenant_id, user_id, role)
SELECT u.tenant_id, u.id, s.role
FROM seed_user s JOIN public.app_user u ON u.issuer = :'issuer' AND u.subject = s.subject
WHERE NOT EXISTS (SELECT 1 FROM public.role_grant g WHERE g.user_id = u.id AND g.role = s.role);
COMMIT;
