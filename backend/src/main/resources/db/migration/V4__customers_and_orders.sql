CREATE TABLE operationsflow.customer (
    id uuid PRIMARY KEY,
    reference_number varchar(32) NOT NULL UNIQUE CHECK (reference_number ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    address_line varchar(200) NOT NULL CHECK (length(btrim(address_line)) > 0),
    city varchar(100) NOT NULL CHECK (length(btrim(city)) > 0),
    postal_code varchar(20) NOT NULL CHECK (length(btrim(postal_code)) > 0),
    country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX customer_status_name_idx ON operationsflow.customer(status, name, id);
CREATE FUNCTION operationsflow.guard_customer_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reference_number IS DISTINCT FROM OLD.reference_number THEN
        RAISE EXCEPTION 'Customer reference is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_reference_immutable BEFORE UPDATE ON operationsflow.customer
FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_customer_reference();

CREATE SEQUENCE operationsflow.order_number_seq;
CREATE TABLE operationsflow.sales_order (
    id uuid PRIMARY KEY,
    order_number varchar(32) NOT NULL UNIQUE DEFAULT ('OF-' || nextval('operationsflow.order_number_seq')::text),
    customer_id uuid NOT NULL REFERENCES operationsflow.customer(id) ON DELETE RESTRICT,
    customer_reference varchar(32) NOT NULL,
    customer_name varchar(160) NOT NULL,
    shipping_address_line varchar(200) NOT NULL,
    shipping_city varchar(100) NOT NULL,
    shipping_postal_code varchar(20) NOT NULL,
    shipping_country_code varchar(2) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('RESERVED','CANCELLED','PROCESSING','PARTIALLY_SHIPPED','SHIPPED')),
    reservation_id uuid NOT NULL UNIQUE REFERENCES operationsflow.stock_reservation(id) ON DELETE RESTRICT,
    total_units bigint NOT NULL CHECK (total_units BETWEEN 1 AND 50000000000),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    cancellation_reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'CANCELLED' AND cancellation_reason IS NOT NULL AND length(btrim(cancellation_reason)) > 0) OR (status <> 'CANCELLED' AND cancellation_reason IS NULL))
);
CREATE INDEX order_customer_status_idx ON operationsflow.sales_order(customer_id,status);
CREATE INDEX order_created_idx ON operationsflow.sales_order(created_at,id);
CREATE TABLE operationsflow.order_line (
    order_id uuid NOT NULL REFERENCES operationsflow.sales_order(id) ON DELETE RESTRICT,
    sku_id uuid NOT NULL REFERENCES operationsflow.sku(id) ON DELETE RESTRICT,
    sku_code varchar(64) NOT NULL,
    product_name varchar(160) NOT NULL,
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000000000),
    PRIMARY KEY(order_id,sku_id)
);
CREATE TABLE operationsflow.order_allocation (
    order_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    warehouse_id uuid NOT NULL REFERENCES operationsflow.warehouse(id) ON DELETE RESTRICT,
    warehouse_code varchar(32) NOT NULL,
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000000000),
    PRIMARY KEY(order_id,warehouse_id,sku_id),
    FOREIGN KEY(order_id,sku_id) REFERENCES operationsflow.order_line(order_id,sku_id) ON DELETE RESTRICT
);
CREATE TABLE operationsflow.order_request (
    id uuid PRIMARY KEY,
    request_hash varchar(64) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE operationsflow.stock_reservation ADD COLUMN order_id uuid UNIQUE;
-- Order reservations are inserted before stock locks; the order snapshot follows in the same transaction.
ALTER TABLE operationsflow.stock_reservation ADD CONSTRAINT reservation_order_fk FOREIGN KEY(order_id)
    REFERENCES operationsflow.sales_order(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
CREATE OR REPLACE FUNCTION operationsflow.guard_reservation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.owner_reference IS DISTINCT FROM OLD.owner_reference OR NEW.order_id IS DISTINCT FROM OLD.order_id OR
       (NEW.state IS DISTINCT FROM OLD.state AND (OLD.state <> 'ACTIVE' OR NEW.state NOT IN ('RELEASED', 'CONSUMED'))) THEN
        RAISE EXCEPTION 'Invalid reservation transition or owner change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION operationsflow.guard_order_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Orders cannot be deleted' USING ERRCODE = '23514'; END IF;
    IF (to_jsonb(NEW) - ARRAY['status','version','cancellation_reason','updated_at']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','version','cancellation_reason','updated_at']) THEN
        RAISE EXCEPTION 'Order snapshots are immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT (OLD.status = 'RESERVED' AND NEW.status = 'CANCELLED' AND NEW.version = OLD.version + 1) THEN
        RAISE EXCEPTION 'Unsupported order transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_snapshot_immutable BEFORE UPDATE OR DELETE ON operationsflow.sales_order
FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_order_snapshot();
CREATE TRIGGER order_line_immutable BEFORE UPDATE OR DELETE ON operationsflow.order_line
FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();
CREATE TRIGGER order_allocation_immutable BEFORE UPDATE OR DELETE ON operationsflow.order_allocation
FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();
