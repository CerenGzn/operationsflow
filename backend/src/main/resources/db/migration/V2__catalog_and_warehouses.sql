CREATE TABLE operationsflow.product (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(2000) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE operationsflow.sku (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES operationsflow.product(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$')
);
CREATE INDEX sku_product_idx ON operationsflow.sku(product_id);
CREATE INDEX product_status_name_idx ON operationsflow.product(status, name, id);
CREATE TABLE operationsflow.warehouse (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    address_line varchar(200) NOT NULL CHECK (length(btrim(address_line)) > 0),
    city varchar(100) NOT NULL CHECK (length(btrim(city)) > 0),
    postal_code varchar(20) NOT NULL CHECK (length(btrim(postal_code)) > 0),
    country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX warehouse_status_name_idx ON operationsflow.warehouse(status, name, id);

-- Protect business identifiers even if a future persistence path attempts an update.
CREATE FUNCTION operationsflow.guard_sku_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code OR NEW.product_id IS DISTINCT FROM OLD.product_id THEN
        RAISE EXCEPTION 'SKU identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sku_identity_immutable BEFORE UPDATE ON operationsflow.sku
FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_sku_identity();
CREATE FUNCTION operationsflow.guard_warehouse_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'Warehouse code is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER warehouse_code_immutable BEFORE UPDATE ON operationsflow.warehouse
FOR EACH ROW EXECUTE FUNCTION operationsflow.guard_warehouse_code();
