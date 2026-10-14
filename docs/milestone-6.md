# Milestone 6: warehouse fulfillment

## Lifecycle decided before implementation

Fulfillment is **whole-order dispatch**, not partial shipment. The existing order-owned reservation is an indivisible stock claim. One task per allocated warehouse is created with the order; V6 backfills tasks for existing RESERVED orders. Each task contains immutable allocation quantities and explicit picked quantities.

- Task: PENDING → PICKING → PICKED → PACKED → READY_TO_SHIP → DISPATCHED.
- Picking records an absolute confirmed quantity for one SKU, from zero to its allocated quantity. Corrections are allowed before packing, so PICKED/PICKING may return to an earlier picking state. Every update carries the task version.
- Packing requires every item picked in full and a second explicit verification of all SKU quantities. One package covers the entire warehouse allocation. Package contents are the immutable task lines; they cannot change after packing.
- Shipment preparation requires a package and a carrier/tracking reference. One shipment per warehouse allocation; carrier/tracking pairs are unique (carrier case-insensitive, tracking case-sensitive).
- Warehouses pick, pack and prepare shipments independently. They do not need to progress simultaneously. The order is RESERVED initially, PROCESSING after work begins, READY_TO_SHIP only when all allocations have shipments, and SHIPPED only after whole-order dispatch.
- Dispatch is an explicit order-wide action: all prepared shipments transition together, and the entire reservation is consumed in one transaction. This is a deliberate coordination barrier, not independent physical dispatch. Warehouse staff must hold packages until the order is ready. Partial shipment, split packages and delivery tracking updates are not supported.
- Cancellation is allowed from RESERVED, PROCESSING or READY_TO_SHIP. It releases the entire reservation and marks all tasks/prepared shipments CANCELLED; packages and picked quantities remain historical evidence. Physical picked goods must be returned to their stock locations operationally. SHIPPED orders cannot be cancelled or have stock restored by cancellation.

## Consistency and retries

All mutations acquire the owning order lock first. Fulfillment never changes balances directly: dispatch calls InventoryService.consumeForOrder, following order → reservation → products → warehouses → balances, with existing deterministic inventory ordering. Cancellation uses the same order lock and reservation boundary. Task updates serialize on that order lock and validate versions, preventing lost picking updates. This conservatively serializes workers on the same order, while different orders can proceed concurrently.

Picking/packing never deduct stock. Dispatch writes fulfillment movements, consumes the reservation, updates shipments/tasks/order and appends actor-attributed audit events in the same transaction. Database constraints and existing unique movement effects prevent negative balances/double effects. Rollback removes all changes and audit records.

Repeated picking with the already-recorded quantity is a no-op. Identical packing and shipment preparation requests return their existing records, including retries carrying an older version; changed contents/metadata conflict. Repeated dispatch returns the shipped order without another movement or audit effect. Cancelled tasks reject operational retries. No new idempotency-key store is needed for these uniquely identified resources; order creation retains its existing idempotency contract.

## Security and API

All routes retain session, CSRF, password-change and revocation checks. ADMIN and WAREHOUSE_OPERATOR may mutate fulfillment. SALES_OPERATOR has read-only access. Warehouse operators currently have access to all warehouses; there is no per-user warehouse assignment model. Catalog/warehouse administration remains ADMIN-only; sales order creation/cancellation permissions remain unchanged.

- GET `/api/v1/fulfillment/tasks`: paged picking queue, optional warehouseId/state/query filters.
- GET `/api/v1/fulfillment/orders/{orderId}`: order status, tasks, picked quantities, package and shipment details.
- PUT `/api/v1/fulfillment/tasks/{id}/picks/{skuId}`: absolute quantity and task version.
- POST `/api/v1/fulfillment/tasks/{id}/pack`: task version and complete SKU/quantity verification.
- POST `/api/v1/fulfillment/tasks/{id}/shipment`: task version, carrier and trackingReference.
- POST `/api/v1/fulfillment/orders/{orderId}/dispatch`: atomically dispatch every allocation.
- GET `/api/v1/fulfillment/shipments`: paged shipment history, optional warehouseId/state filters.

Invalid quantities return 400, missing records 404, stale versions and invalid transitions 409. Lock contention remains a retryable 503. Frontend conflicts require refreshing current state; it never silently resubmits a changed mutation. Audit events contain authenticated actor and target UUID; picking events also capture the SKU UUID and before/after confirmed quantities so corrections remain traceable. Credentials and raw request bodies are never captured.

V6 adds task, task-line, package and shipment tables, quantity/uniqueness/foreign-key constraints, immutable snapshot guards and allowed order transitions. Existing migrations and ledger semantics remain unchanged. Package/shipment metadata correction, carrier integration, returns, partial shipments, warehouse assignments and dispatch scheduling are deferred. The existing single-process authentication limitations still apply. No deployment is included.
