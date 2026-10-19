-- Additive only. No historical backfill and no RabbitMQ dependency.
CREATE TABLE operationsflow.event_outbox (
    event_id uuid PRIMARY KEY,
    event_type varchar(32) NOT NULL CHECK (event_type IN ('ORDER_RESERVED','ORDER_CANCELLED','ORDER_SHIPPED')),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    order_id uuid NOT NULL REFERENCES operationsflow.sales_order(id),
    occurred_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL CHECK (
        jsonb_typeof(payload) = 'object' AND payload ? 'totalUnits'
        AND payload - 'totalUnits' = '{}'::jsonb
        AND jsonb_typeof(payload->'totalUnits') = 'number'
        AND (payload->>'totalUnits') ~ '^[1-9][0-9]{0,10}$'
        AND (payload->>'totalUnits')::numeric <= 50000000000),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','IN_FLIGHT','PUBLISHED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    published_at timestamptz,
    last_error varchar(40),
    UNIQUE (order_id,event_type),
    CHECK ((state = 'IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX outbox_pending_idx ON operationsflow.event_outbox(next_attempt_at,event_id) WHERE state='PENDING';
CREATE INDEX outbox_lease_idx ON operationsflow.event_outbox(lease_until,event_id) WHERE state='IN_FLIGHT';
-- Inbox and derived ledger commit together. No FK to order truth: delivery is independent.
CREATE TABLE operationsflow.event_inbox (
    event_id uuid PRIMARY KEY,
    content_hash char(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE operationsflow.order_event_ledger (
    event_id uuid PRIMARY KEY REFERENCES operationsflow.event_inbox(event_id),
    order_id uuid NOT NULL,
    event_type varchar(32) NOT NULL CHECK (event_type IN ('ORDER_RESERVED','ORDER_CANCELLED','ORDER_SHIPPED')),
    occurred_at timestamptz NOT NULL,
    total_units bigint NOT NULL CHECK (total_units > 0),
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX order_event_ledger_order_idx ON operationsflow.order_event_ledger(order_id,occurred_at,event_id);
