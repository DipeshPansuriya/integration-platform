export interface paths {
    "/api/v1/admin/contracts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listContracts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["saveDraft_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/versions/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["importVersion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/versions/{versionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["read_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/versions/{versionId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["approveContractVersion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/versions/{versionId}/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submit_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/contracts/{contractId}/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["versionsOf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["read"];
        put: operations["saveDraft"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["approve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["publish"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}/rule-sets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listRuleSets"];
        put?: never;
        post: operations["createRuleSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/flows/{flowId}/versions/{versionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["editVersion"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/rule-sets/{ruleSetId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["readRuleSet"];
        put: operations["updateRuleSet"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/rule-sets/{ruleSetId}/evaluate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["evaluateRuleSet"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/systems": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listSystems"];
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/systems/{systemId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["readSystem"];
        put: operations["updateSystem"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/systems/{systemId}/test-connection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["testConnection"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @description A config admin refusal: exactly one of error or refusal is present. */
        ConfigErrorBody: {
            /** @description The refusal message (validation, not found, conflict). */
            error?: string;
            /** @description The named refusal code (NO_BOUND_USER, AUTHOR_IS_APPROVER, NOT_PENDING_APPROVAL). */
            refusal?: string;
        };
        /** @description One contract with its versions side by side, in label order */
        ContractBody: {
            code?: string;
            contractId?: string;
            id?: string;
            name?: string;
            /** @enum {string} */
            role?: "SOURCE_REQUEST" | "SOURCE_RESPONSE" | "TARGET_REQUEST" | "TARGET_RESPONSE";
            system?: string;
            versions?: components["schemas"]["ContractVersionSummary"][];
        };
        /** @description A refused import: the refusal and, for a document that does not parse, the parser's errors */
        ContractImportErrorBody: {
            error?: string;
            parserErrors?: string[];
        };
        /** @description Every contract of the tenant */
        ContractListBody: {
            contracts?: components["schemas"]["ContractBody"][];
        };
        /** @description One contract version with its schema */
        ContractVersionBody: {
            approverId?: string | null;
            authorId?: string;
            changeNote?: string | null;
            contractId?: string;
            createdAt?: string;
            /** @description Lineage: the version this one derives from */
            derivedFromVersionId?: string | null;
            id?: string;
            /** @description The canonical JSON Schema (draft 2020-12) */
            jsonSchema?: {
                [key: string]: unknown;
            };
            /** @description The original OpenAPI document, or null for a JSON Schema import */
            openapiDocument?: {
                [key: string]: unknown;
            } | null;
            /** @enum {string} */
            role?: "SOURCE_REQUEST" | "SOURCE_RESPONSE" | "TARGET_REQUEST" | "TARGET_RESPONSE";
            /** @description The same schema under its E3 name (draft screens) */
            sourceSchema?: {
                [key: string]: unknown;
            };
            /** @enum {string} */
            status?: "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "RETIRED";
            /** @description The system code the contract describes */
            system?: string;
            /**
             * @description major.minor
             * @example 2.0
             */
            versionLabel?: string;
        };
        /** @description One version in a side-by-side listing */
        ContractVersionSummary: {
            approverId?: string | null;
            authorId?: string;
            createdAt?: string;
            derivedFromVersionId?: string | null;
            id?: string;
            /** @enum {string} */
            status?: "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "RETIRED";
            versionLabel?: string;
        };
        JsonNode: {
            array?: boolean;
            bigDecimal?: boolean;
            bigInteger?: boolean;
            binary?: boolean;
            boolean?: boolean;
            container?: boolean;
            double?: boolean;
            embeddedValue?: boolean;
            empty?: boolean;
            float?: boolean;
            floatingPointNumber?: boolean;
            int?: boolean;
            integralNumber?: boolean;
            long?: boolean;
            missingNode?: boolean;
            /** @enum {string} */
            nodeType?: "ARRAY" | "BINARY" | "BOOLEAN" | "MISSING" | "NULL" | "NUMBER" | "OBJECT" | "POJO" | "STRING";
            null?: boolean;
            number?: boolean;
            object?: boolean;
            pojo?: boolean;
            short?: boolean;
            string?: boolean;
            /** @deprecated */
            textual?: boolean;
            valueNode?: boolean;
        };
        Me: {
            tenantCode?: string;
            /** Format: uuid */
            tenantId?: string;
            timeZone?: string;
            /** Format: uuid */
            userId?: string;
        };
        RuleTableCell: {
            column?: string;
            node?: string;
            /** Format: int32 */
            row?: number;
        };
        /** @description RULE_TABLE_INVALID: the refused cell and the engine's reason; or {error} for a validation refusal. */
        RuleTableInvalidBody: {
            /** @description The refused cell; null members when the model as a whole is unreadable */
            cell?: components["schemas"]["RuleTableCell"];
            /** @description RULE_TABLE_INVALID, or the validation message */
            error?: string;
            /** @description Why the table is refused (the engine's own reason) */
            reason?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listContracts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Every contract of the tenant with its versions side by side */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractListBody"];
                };
            };
            /** @description NO_BOUND_USER, or ROLE_NOT_GRANTED: reading configuration needs a configuration role */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    saveDraft_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description A validation refusal names the refused field or value */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a concurrent save took the same contract or version label */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    importVersion: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description The new DRAFT version, its schema imported */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractVersionBody"];
                };
            };
            /** @description A refused field, or a document that does not parse - with the parser's errors */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractImportErrorBody"];
                };
            };
            /** @description NO_BOUND_USER, or ROLE_NOT_GRANTED: editing configuration needs TENANT_ADMIN or CONFIGURATOR */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: the contract already has this version label */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    read_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                versionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The version with its schema, any status (RETIRED stays readable) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractVersionBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    approveContractVersion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                versionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The version, now APPROVED by the caller */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractVersionBody"];
                };
            };
            /** @description NO_BOUND_USER, ROLE_NOT_GRANTED, or AUTHOR_IS_APPROVER: the author never approves their own version */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the version is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NOT_PENDING_APPROVAL: only a submitted version is approvable */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    submit_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                versionId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description A validation refusal names the refused field or value */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: only a DRAFT contract version submits */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    versionsOf: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                contractId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One contract with its versions side by side */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ContractBody"];
                };
            };
            /** @description NO_BOUND_USER, or ROLE_NOT_GRANTED: reading configuration needs a configuration role */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the contract is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    }[];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description A validation refusal names the refused field or value */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a named destination clashes with a registered system code */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    read: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    saveDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description A validation refusal names the refused field or value */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a named destination clashes with a registered system code */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    approve: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER, or AUTHOR_IS_APPROVER: the author never approves their own version */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: NOT_PENDING_APPROVAL, or no version is pending approval */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    publish: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: the flow has no approved version to publish */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    listRuleSets: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK: the flow's rule sets in authoring order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    }[];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow reading */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the flow is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    createRuleSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description Created: {id, flowId, kind, name, content} */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description RULE_TABLE_INVALID with the cell and the reason, or a validation refusal (kind, name) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RuleTableInvalidBody"];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow editing */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the flow is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a lookup table of that name exists */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    submit: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description A validation refusal names the refused field or value */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: the flow has no draft version to submit */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    editVersion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                flowId: string;
                versionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description NO_BOUND_USER: the bearer resolves to no directory user */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: version content is immutable - save a new draft on the flow */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    readRuleSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleSetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK: {id, flowId, kind, name, content} */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow reading */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the rule set is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    updateRuleSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleSetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK: {id, flowId, kind, name, content} */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description RULE_TABLE_INVALID with the cell and the reason, or a validation refusal (name) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RuleTableInvalidBody"];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow editing */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the rule set is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a lookup table of that name exists */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    evaluateRuleSet: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleSetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK: {ruleSetId, kind, output, violations} */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description The body carries no input */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description The role does not allow reading configuration */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the rule set is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    listSystems: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Every system of the bound tenant, ordered by code */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    }[];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow the operation */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description Every validation error is listed (errors) and joined (error) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow the operation */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Conflict: a system with this code is already registered */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    readSystem: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                systemId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow the operation */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    updateSystem: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                systemId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JsonNode"];
            };
        };
        responses: {
            /** @description OK: the edited system */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description Every validation error is listed (errors) and joined (error) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow the operation */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    testConnection: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                systemId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The result: SUCCESS (httpStatus, responseTimeMs), REFUSED (reason, before any data is sent), FAILED (reason), NOT_AVAILABLE_YET (the type's test arrives with its adapter) or NOT_CONFIGURED */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description NO_BOUND_USER, or the role does not allow the operation */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description Not found: the row is not visible to the bound tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
            /** @description TEST_CONNECTION_RATE_LIMITED: over the per-user limit */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigErrorBody"];
                };
            };
        };
    };
    me: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
}
