-- TEST FIXTURE ONLY (T147). platform-tenancy's own test database has no platform-config migrations, so the
-- scheduler schema (owned by platform-config V2_2_1400__scheduler_schema.sql) is absent, yet PrincipalTenantResolver
-- reads scheduler.operator_credential through JdbcSchedulerRegistry (52d3f63). This stub copies exactly that table,
-- its schema and its grants from V2_2_1400 - same version, so the order matches platform-app. Never on a production
-- classpath; change it only when V2_2_1400's operator_credential changes.

CREATE SCHEMA scheduler;
REVOKE ALL ON SCHEMA scheduler FROM PUBLIC;
GRANT USAGE ON SCHEMA scheduler TO ipaas_app;

CREATE TABLE scheduler.operator_credential (
    issuer            varchar(200)                   NOT NULL,
    client_id         varchar(200)                   NOT NULL,
    status            varchar(20)                    NOT NULL          DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'REVOKED')),
    scopes            varchar(500)                   NOT NULL          DEFAULT 'platform-api',
    created_at        timestamp with time zone       NOT NULL          DEFAULT now(),
    revoked_at        timestamp with time zone,
    CONSTRAINT operator_credential_pk PRIMARY KEY (issuer, client_id)
);

REVOKE ALL ON scheduler.operator_credential FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.operator_credential TO ipaas_app;
