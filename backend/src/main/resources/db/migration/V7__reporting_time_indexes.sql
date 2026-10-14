-- New global date-range reports cannot use the warehouse/SKU-leading ledger index.
CREATE INDEX movement_created_reporting_idx ON operationsflow.inventory_movement(created_at,id);
CREATE INDEX fulfillment_created_reporting_idx ON operationsflow.fulfillment_task(created_at,id);
-- Throughput filters dispatch time, not shipment creation time.
CREATE INDEX shipment_dispatched_reporting_idx ON operationsflow.shipment(dispatched_at,id) WHERE dispatched_at IS NOT NULL;
-- Cancellation activity uses the terminal transition timestamp, not order creation.
CREATE INDEX order_cancelled_reporting_idx ON operationsflow.sales_order(updated_at,id) WHERE status='CANCELLED';
