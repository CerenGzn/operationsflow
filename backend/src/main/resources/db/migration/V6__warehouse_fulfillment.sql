ALTER TABLE operationsflow.sales_order DROP CONSTRAINT sales_order_status_check;
ALTER TABLE operationsflow.sales_order ADD CONSTRAINT sales_order_status_check CHECK (status IN ('RESERVED','PROCESSING','READY_TO_SHIP','SHIPPED','CANCELLED'));
CREATE OR REPLACE FUNCTION operationsflow.guard_order_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Orders cannot be deleted' USING ERRCODE = '23514'; END IF;
    IF (to_jsonb(NEW) - ARRAY['status','version','cancellation_reason','updated_at']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','version','cancellation_reason','updated_at']) THEN
        RAISE EXCEPTION 'Order snapshots are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NOT (
       (OLD.status = 'RESERVED' AND NEW.status IN ('PROCESSING','CANCELLED')) OR
       (OLD.status = 'PROCESSING' AND NEW.status IN ('READY_TO_SHIP','CANCELLED')) OR
       (OLD.status = 'READY_TO_SHIP' AND NEW.status IN ('SHIPPED','CANCELLED'))) THEN
        RAISE EXCEPTION 'Unsupported order transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
ALTER TABLE operationsflow.order_allocation ADD CONSTRAINT allocation_exact_quantity UNIQUE(order_id,warehouse_id,sku_id,quantity);
CREATE TABLE operationsflow.fulfillment_task (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES operationsflow.sales_order(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL REFERENCES operationsflow.warehouse(id) ON DELETE RESTRICT,
    state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','PICKING','PICKED','PACKED','READY_TO_SHIP','DISPATCHED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(order_id,warehouse_id), UNIQUE(id,order_id,warehouse_id)
);
CREATE INDEX fulfillment_queue_idx ON operationsflow.fulfillment_task(warehouse_id,state,created_at,id);
CREATE TABLE operationsflow.fulfillment_line (
    task_id uuid NOT NULL,
    order_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000000000),
    picked_quantity bigint NOT NULL DEFAULT 0 CHECK (picked_quantity BETWEEN 0 AND quantity),
    PRIMARY KEY(task_id,sku_id),
    FOREIGN KEY(task_id,order_id,warehouse_id) REFERENCES operationsflow.fulfillment_task(id,order_id,warehouse_id) ON DELETE RESTRICT,
    FOREIGN KEY(order_id,warehouse_id,sku_id,quantity) REFERENCES operationsflow.order_allocation(order_id,warehouse_id,sku_id,quantity) ON DELETE RESTRICT
);
CREATE TABLE operationsflow.fulfillment_package (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL UNIQUE REFERENCES operationsflow.fulfillment_task(id) ON DELETE RESTRICT,
    packed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id,task_id)
);
CREATE TRIGGER package_immutable BEFORE UPDATE OR DELETE ON operationsflow.fulfillment_package FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();
CREATE TABLE operationsflow.shipment (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL UNIQUE REFERENCES operationsflow.fulfillment_task(id) ON DELETE RESTRICT,
    package_id uuid NOT NULL UNIQUE,
    carrier varchar(100) NOT NULL CHECK (length(btrim(carrier)) > 0 AND carrier=btrim(carrier)),
    tracking_reference varchar(160) NOT NULL CHECK (length(btrim(tracking_reference)) > 0 AND tracking_reference=btrim(tracking_reference)),
    state varchar(16) NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','DISPATCHED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    dispatched_at timestamptz,
    FOREIGN KEY(package_id,task_id) REFERENCES operationsflow.fulfillment_package(id,task_id) ON DELETE RESTRICT,
    CHECK ((state='DISPATCHED') = (dispatched_at IS NOT NULL))
);
CREATE UNIQUE INDEX shipment_tracking_unique ON operationsflow.shipment(lower(carrier),tracking_reference);
CREATE INDEX shipment_history_idx ON operationsflow.shipment(created_at,id);
CREATE FUNCTION operationsflow.guard_fulfillment_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Fulfillment history cannot be deleted' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-ARRAY['state','version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','updated_at']) OR NEW.version<>OLD.version+1 OR NOT (
      (OLD.state IN ('PENDING','PICKING','PICKED') AND NEW.state IN ('PENDING','PICKING','PICKED')) OR
      (OLD.state='PICKED' AND NEW.state='PACKED') OR (OLD.state='PACKED' AND NEW.state='READY_TO_SHIP') OR
      (OLD.state='READY_TO_SHIP' AND NEW.state='DISPATCHED') OR (OLD.state NOT IN ('DISPATCHED','CANCELLED') AND NEW.state='CANCELLED')) THEN
      RAISE EXCEPTION 'Invalid fulfillment task transition' USING ERRCODE='23514';
    END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER fulfillment_task_guard BEFORE UPDATE OR DELETE ON operationsflow.fulfillment_task FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_fulfillment_task();
CREATE FUNCTION operationsflow.guard_fulfillment_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Fulfillment lines cannot be deleted' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-'picked_quantity') IS DISTINCT FROM (to_jsonb(OLD)-'picked_quantity') OR
       NOT EXISTS(SELECT 1 FROM operationsflow.fulfillment_task WHERE id=OLD.task_id AND state IN ('PENDING','PICKING','PICKED')) THEN
      RAISE EXCEPTION 'Fulfillment line is immutable after packing' USING ERRCODE='23514';
    END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER fulfillment_line_guard BEFORE UPDATE OR DELETE ON operationsflow.fulfillment_line FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_fulfillment_line();
CREATE FUNCTION operationsflow.guard_shipment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Shipment history cannot be deleted' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-ARRAY['state','dispatched_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','dispatched_at']) OR
       OLD.state<>'READY' OR NEW.state NOT IN ('DISPATCHED','CANCELLED') THEN
      RAISE EXCEPTION 'Invalid shipment transition' USING ERRCODE='23514';
    END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER shipment_guard BEFORE UPDATE OR DELETE ON operationsflow.shipment FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_shipment();
-- Existing reserved orders receive tasks without fabricated historical user attribution.
INSERT INTO operationsflow.fulfillment_task(id,order_id,warehouse_id)
SELECT gen_random_uuid(),a.order_id,a.warehouse_id FROM operationsflow.order_allocation a JOIN operationsflow.sales_order o ON o.id=a.order_id WHERE o.status='RESERVED' GROUP BY a.order_id,a.warehouse_id;
INSERT INTO operationsflow.fulfillment_line(task_id,order_id,warehouse_id,sku_id,quantity)
SELECT t.id,a.order_id,a.warehouse_id,a.sku_id,a.quantity FROM operationsflow.fulfillment_task t JOIN operationsflow.order_allocation a ON a.order_id=t.order_id AND a.warehouse_id=t.warehouse_id;
