-- Lane B proof scenarios (T-014, T-018) - runs as ipaas_app_login (the restricted application role).
-- Every action is tenant-stamped (set_config local, R11) and every probe records the SQLSTATE that
-- fired, so the same file serves the RED run (schema absent, probes report 42P01) and the GREEN run
-- (the schema's refusal contract, exact SQLSTATEs). The report file turns this into the verdict.
--
-- ADR-007 user ids are plain uuids - no directory dependency: author U1 = ...a1, approver U2 = ...a2.

-- T-014a "The interface carries its business-key rule and ordering setting" - fixtures first:
-- system, its target endpoint, the source contract and a 1.0 draft of it, the interface, two
-- destinations with routes, one mapping per route, and the draft flow version (ADR-011 Decision 2).
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    INSERT INTO public.system (id, tenant_id, code, name, direction)
        VALUES ('b7e8c2d0-1111-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a', 'ERP', 'ERP Cloud', 'BOTH');
    INSERT INTO public.proof_probe VALUES ('t014_system_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_system_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url, http_method,
                                        auth_method, credential_ref, timeout_ms)
        VALUES ('b7e8c2d0-2222-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-1111-4000-8000-000000000001', 'TGT_INVOICES', 'Invoices API', 'https://erp.example.com/v1/invoices', 'POST',
                'OAUTH2_CLIENT_CREDENTIALS', 'openbao/secret/erp', 30000);
    INSERT INTO public.proof_probe VALUES ('t014_target_endpoint_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_target_endpoint_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)
        VALUES ('b7e8c2d0-3333-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-1111-4000-8000-000000000001',
                'CTS_INVOICE', 'SOURCE_REQUEST', 'CTS invoice request');
    INSERT INTO public.proof_probe VALUES ('t014_contract_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_contract_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status, json_schema_draft_2020_12, author_id)
        VALUES ('b7e8c2d0-4444-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-3333-4000-8000-000000000001', '1.0', 'DRAFT',
                '{"type": "object", "required": ["orderId"]}'::jsonb,
                'b7e8c2d0-aaaa-4000-8000-0000000000a1');
    INSERT INTO public.proof_probe VALUES ('t014_contract_version_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_contract_version_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.interface (id, tenant_id, code, name, mode)
        VALUES ('b7e8c2d0-5555-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a', 'CTS_TO_ERP_INVOICES', 'CTS to ERP invoices', 'ASYNC');
    INSERT INTO public.proof_probe VALUES ('t014_interface_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_interface_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

-- A second target endpoint for the second destination.
DO $$
BEGIN
    INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url, http_method,
                                        auth_method, credential_ref, timeout_ms)
        VALUES ('b7e8c2d0-2222-4000-8000-000000000002', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-1111-4000-8000-000000000001', 'TGT_ARCHIVE', 'Archive API', 'https://erp.example.com/v1/archive', 'POST',
                'API_KEY', 'openbao/secret/archive', 10000);
    INSERT INTO public.proof_probe VALUES ('t014_second_endpoint_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_second_endpoint_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.route (id, tenant_id, interface_id, target_endpoint_id, sequence)
        VALUES ('b7e8c2d0-6666-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001',
                'b7e8c2d0-2222-4000-8000-000000000001', 1);
    INSERT INTO public.route (id, tenant_id, interface_id, target_endpoint_id, sequence)
        VALUES ('b7e8c2d0-6666-4000-8000-000000000002', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001',
                'b7e8c2d0-2222-4000-8000-000000000002', 2);
    INSERT INTO public.proof_probe VALUES ('t014_routes_insert', 'NONE', 'NONE', 'inserted 2');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_routes_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

DO $$
BEGIN
    INSERT INTO public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression, test_samples)
        VALUES ('b7e8c2d0-7777-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-6666-4000-8000-000000000001',
                '[{"from": "orderId", "to": "InvoiceNo"}]'::jsonb,
                'InvoiceNo := orderId',
                '[{"give": {"orderId": "1"}, "expect": {"InvoiceNo": "1"}}]'::jsonb);
    INSERT INTO public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression)
        VALUES ('b7e8c2d0-7777-4000-8000-000000000002', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-6666-4000-8000-000000000002',
                '[{"from": "orderId", "to": "OrderNumber"}]'::jsonb, 'OrderNumber := orderId');
    INSERT INTO public.proof_probe VALUES ('t014_mappings_insert', 'NONE', 'NONE', 'inserted 2');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_mappings_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

-- The draft flow version: business-key rule mandatory, the source schema lives only in
-- contract_version and is referenced by id, never copied (ADR-011 Decision 2). The snapshot
-- (interface + routes + mappings) is one canonical serialization in the row from the first save.
DO $$
BEGIN
    INSERT INTO public.interface_version (id, tenant_id, interface_id, version, business_key_rule,
                                          source_contract_version_id, author_id, snapshot)
        VALUES ('b7e8c2d0-8888-4000-8000-000000000001', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001', 1,
                'body.orderId, mandatory + unique per 30 days', 'b7e8c2d0-4444-4000-8000-000000000001',
                'b7e8c2d0-aaaa-4000-8000-0000000000a1',
                '{"interface": "CTS_TO_ERP_INVOICES", "routes": 2, "mappings": 2}'::jsonb);
    -- A draft save also advances the interface's current version (the service pairs the two writes
    -- in one transaction; the schema enforces published_version <= current_version, V2_2_3).
    UPDATE public.interface SET current_version = 1
        WHERE id = 'b7e8c2d0-5555-4000-8000-000000000001';
    INSERT INTO public.proof_probe VALUES ('t014_draft_version_insert', 'NONE', 'NONE', 'inserted');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_draft_version_insert', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;

-- T-014a assertions: the ordering setting is stored with the interface (PER_KEY default, ADR-011
-- Decision 6) and the business-key rule with its draft version; the source schema is reachable
-- through the reference and is NOT copied onto the interface or the version.
BEGIN;
SELECT set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true);

DO $$
BEGIN
    IF (SELECT ordering FROM public.interface WHERE id = 'b7e8c2d0-5555-4000-8000-000000000001') = 'PER_KEY'
       AND (SELECT business_key_rule FROM public.interface_version WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001')
           = 'body.orderId, mandatory + unique per 30 days'
       AND (SELECT json_schema_draft_2020_12 ->> 'type' FROM public.contract_version
            WHERE id = (SELECT source_contract_version_id FROM public.interface_version
                        WHERE id = 'b7e8c2d0-8888-4000-8000-000000000001')) = 'object' THEN
        INSERT INTO public.proof_probe VALUES ('t014a_rule_and_ordering_stored', 'NONE', 'NONE', 'as ADR-011');
    ELSE
        INSERT INTO public.proof_probe VALUES ('t014a_rule_and_ordering_stored', 'NONE', 'FAIL',
                                               'values not stored as required');
    END IF;
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014a_rule_and_ordering_stored', 'NONE', SQLSTATE, left(SQLERRM, 200));
END $$;

-- @negative T-014 "A flow without a business-key rule is refused".
DO $$
BEGIN
    INSERT INTO public.interface_version (id, tenant_id, interface_id, version, business_key_rule,
                                          source_contract_version_id, author_id, snapshot)
        VALUES ('b7e8c2d0-8888-4000-8000-000000000002', '0000000a-0000-4000-8000-00000000000a',
                'b7e8c2d0-5555-4000-8000-000000000001', 2,
                NULL, 'b7e8c2d0-4444-4000-8000-000000000001',
                'b7e8c2d0-aaaa-4000-8000-0000000000a1', '{"x": 1}'::jsonb);
    INSERT INTO public.proof_probe VALUES ('t014_draft_without_rule_refused', '23502', 'NONE',
                                           'refusal did not fire');
EXCEPTION WHEN OTHERS THEN
    INSERT INTO public.proof_probe VALUES ('t014_draft_without_rule_refused', '23502', SQLSTATE, left(SQLERRM, 200));
END $$;
COMMIT;