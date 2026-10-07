-- E4 lane D, T-097 (ADR-007 Decision 1): the console's server sessions (Spring Session JDBC, PostgreSQL schema of
-- spring-session-jdbc 4.1, schema-postgresql.sql), so every instance stays stateless. Created here because Flyway owns
-- the schema (spring.session.jdbc.initialize-schema=never in tenancy-console.yml).
--
-- Own schema console_session, NOT public: a session row is not tenant data. It is created before a tenant is bound
-- (the login), it is found only by its random session id (128-bit, from the HttpOnly cookie) and it holds the login's
-- serialized security context and authorized client (the access and refresh tokens never leave the server). There is
-- no tenant column to filter by, so no row-level security - access is by grant: the application role reads and
-- writes it, PUBLIC holds nothing, and the tenant-table grant gates on schema public stay as they are.
--
-- Rollback (Flyway Community has no undo; run as the migration owner, then delete this version from
-- flyway_schema_history): DROP SCHEMA console_session CASCADE;  -- every console session ends (users log in again).

CREATE SCHEMA console_session;
REVOKE ALL ON SCHEMA console_session FROM PUBLIC;
GRANT USAGE ON SCHEMA console_session TO ipaas_app;

CREATE TABLE console_session.spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval integer      NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON console_session.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON console_session.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON console_session.spring_session (principal_name);

CREATE TABLE console_session.spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES console_session.spring_session (primary_id) ON DELETE CASCADE
);

REVOKE ALL ON console_session.spring_session, console_session.spring_session_attributes FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON console_session.spring_session, console_session.spring_session_attributes TO ipaas_app;
