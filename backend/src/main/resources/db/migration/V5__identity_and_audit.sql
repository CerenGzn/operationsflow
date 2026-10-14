CREATE TABLE operationsflow.app_user (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND length(email) > 3),
    display_name varchar(100) NOT NULL CHECK (length(btrim(display_name)) > 0),
    password_hash varchar(100) NOT NULL CHECK (password_hash ~ '^\$2[aby]\$12\$'),
    role varchar(32) NOT NULL CHECK (role IN ('ADMIN','WAREHOUSE_OPERATOR','SALES_OPERATOR')),
    active boolean NOT NULL DEFAULT true,
    must_change_password boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    security_version bigint NOT NULL DEFAULT 0 CHECK (security_version >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- A single short-lived lock serializes changes to administrator membership and bootstrap.
CREATE TABLE operationsflow.identity_guard (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO operationsflow.identity_guard VALUES (1);
CREATE TABLE operationsflow.audit_event (
    id uuid PRIMARY KEY,
    actor_id uuid REFERENCES operationsflow.app_user(id) ON DELETE RESTRICT,
    actor_kind varchar(16) NOT NULL CHECK (actor_kind IN ('USER','ANONYMOUS','SYSTEM')),
    action varchar(64) NOT NULL,
    target_id uuid,
    detail varchar(300) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((actor_kind = 'USER' AND actor_id IS NOT NULL) OR (actor_kind <> 'USER' AND actor_id IS NULL))
);
CREATE INDEX audit_created_idx ON operationsflow.audit_event(created_at,id);
CREATE INDEX audit_actor_idx ON operationsflow.audit_event(actor_id,created_at);
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON operationsflow.audit_event
FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();
-- Historical inventory actors remain UNAUTHENTICATED. New movements use user:<UUID>.
