CREATE TABLE operationsflow.inventory_balance (
    warehouse_id uuid NOT NULL REFERENCES operationsflow.warehouse(id) ON DELETE RESTRICT,
    sku_id uuid NOT NULL REFERENCES operationsflow.sku(id) ON DELETE RESTRICT,
    on_hand bigint NOT NULL DEFAULT 0,
    reserved bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (warehouse_id, sku_id),
    CONSTRAINT valid_stock CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand AND on_hand <= 1000000000000)
);
CREATE INDEX balance_sku_idx ON operationsflow.inventory_balance(sku_id);
CREATE TABLE operationsflow.stock_operation (
    id uuid PRIMARY KEY,
    operation varchar(32) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE operationsflow.stock_reservation (
    id uuid PRIMARY KEY,
    owner_reference varchar(100) NOT NULL UNIQUE CHECK (owner_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$'),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX reservation_state_idx ON operationsflow.stock_reservation(state, created_at, id);
CREATE TABLE operationsflow.stock_reservation_line (
    reservation_id uuid NOT NULL REFERENCES operationsflow.stock_reservation(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    quantity bigint NOT NULL CHECK (quantity BETWEEN 1 AND 1000000000),
    PRIMARY KEY (reservation_id, warehouse_id, sku_id),
    FOREIGN KEY (warehouse_id, sku_id) REFERENCES operationsflow.inventory_balance(warehouse_id, sku_id) ON DELETE RESTRICT
);
CREATE TABLE operationsflow.inventory_movement (
    id uuid PRIMARY KEY,
    warehouse_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    type varchar(16) NOT NULL CHECK (type IN ('RECEIPT', 'ADJUSTMENT', 'RESERVATION', 'RELEASE', 'FULFILLMENT')),
    on_hand_delta bigint NOT NULL,
    reserved_delta bigint NOT NULL,
    on_hand_after bigint NOT NULL,
    reserved_after bigint NOT NULL,
    reservation_id uuid REFERENCES operationsflow.stock_reservation(id) ON DELETE RESTRICT,
    operation_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    actor varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (warehouse_id, sku_id) REFERENCES operationsflow.inventory_balance(warehouse_id, sku_id) ON DELETE RESTRICT,
    UNIQUE (operation_id, warehouse_id, sku_id),
    CHECK (on_hand_after >= 0 AND reserved_after >= 0 AND reserved_after <= on_hand_after),
    CHECK (
        (type = 'RECEIPT' AND on_hand_delta > 0 AND reserved_delta = 0 AND reservation_id IS NULL) OR
        (type = 'ADJUSTMENT' AND on_hand_delta <> 0 AND reserved_delta = 0 AND reservation_id IS NULL) OR
        (type = 'RESERVATION' AND on_hand_delta = 0 AND reserved_delta > 0 AND reservation_id IS NOT NULL) OR
        (type = 'RELEASE' AND on_hand_delta = 0 AND reserved_delta < 0 AND reservation_id IS NOT NULL) OR
        (type = 'FULFILLMENT' AND on_hand_delta < 0 AND on_hand_delta = reserved_delta AND reservation_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX one_reservation_effect ON operationsflow.inventory_movement(reservation_id, warehouse_id, sku_id, type)
    WHERE reservation_id IS NOT NULL;
CREATE INDEX movement_balance_time_idx ON operationsflow.inventory_movement(warehouse_id, sku_id, created_at, id);
CREATE FUNCTION operationsflow.reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Inventory history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER movement_append_only BEFORE UPDATE OR DELETE ON operationsflow.inventory_movement
FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();
CREATE TRIGGER reservation_lines_immutable BEFORE UPDATE OR DELETE ON operationsflow.stock_reservation_line
FOR EACH ROW EXECUTE FUNCTION operationsflow.reject_ledger_mutation();

CREATE FUNCTION operationsflow.guard_reservation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.owner_reference IS DISTINCT FROM OLD.owner_reference OR
       (NEW.state IS DISTINCT FROM OLD.state AND (OLD.state <> 'ACTIVE' OR NEW.state NOT IN ('RELEASED', 'CONSUMED'))) THEN
        RAISE EXCEPTION 'Invalid reservation transition or owner change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reservation_transition_guard BEFORE UPDATE ON operationsflow.stock_reservation
FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_reservation_transition();
