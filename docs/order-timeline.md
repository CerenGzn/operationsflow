# Order status timeline

Implemented in Milestone 16 source; **not deployed or verified on Render by this change**. Existing live smoke-test claims do not cover the timeline. Deployment remains a separately approved owner action.

The employee Orders screen shows a read-only timeline in its existing details panel. This is operational history, not customer delivery tracking. Refresh reloads the timeline; it does not poll or change stock. Times are explicitly UTC.

## Data and meaning

`GET /api/v1/orders/{id}/timeline` returns only order ID, current status and milestone code/label/progress/time/detail. It excludes customer details, shipping addresses, tracking references, carrier names and audit actor/detail fields.

| Milestone | Confirmation and timestamp |
| --- | --- |
| Order Confirmed | Persisted order creation time. Creation and reservation already commit atomically. |
| Preparing Order | Earliest persisted `PICK_CONFIRMED` audit event for the order's fulfillment tasks. Confirms that picking started, not that all picking is complete. Later quantity corrections do not erase that historical event. |
| Packed | Every allocated warehouse has a persisted package. Completion time is the latest package `packed_at`. Partial completion shows counts and no order-wide completion timestamp. |
| Shipment Prepared | Shown only when at least one shipment record exists. All required shipments must exist for completion; latest shipment `created_at` is the aggregate time. Preparation does not imply dispatch. |
| Shipped | Order status is SHIPPED and every warehouse shipment has `dispatched_at`. Latest dispatch timestamp is used. This records the operator's whole-order dispatch, not independent carrier handover or delivery evidence. |
| Cancelled | Current order is CANCELLED. Timestamp is the first persisted `ORDER_CANCELLED` audit event, or explicitly unavailable for legacy history. Earlier confirmed packing/preparation remains historical; unfinished stages become Not reached. |

Pending, In progress, Confirmed and Not reached use text as well as visual markers. No timestamp is inferred from `updated_at`, another stage, the browser clock or a message. Legacy orders without retained picking/cancellation audit events have no invented timestamps. A missing picking event means no confirmed picking milestone; absence is not proof that historical work never happened. Partial warehouse counts remain visible after cancellation.

## Consistency and access

The additive read endpoint uses a single parameterized PostgreSQL statement, giving one statement-level MVCC snapshot across order, fulfillment and audit records. No locks or business writes are added, and existing transactions, stock calculations and write APIs are unchanged. One-to-one package/task and shipment/task constraints avoid duplicated aggregate counts. Database-side aggregation avoids loading per-task entities or N+1 queries.

ADMIN, SALES_OPERATOR and WAREHOUSE_OPERATOR retain their existing raw-order read access. DEMO_VIEWER remains limited to sanitized reports: this endpoint and raw order details return 403. No role, session, CSRF or user-management policy changes are made. Anonymous access returns 401. The endpoint is GET-only and cannot mutate the database.

No Flyway migration or backfill is needed. Reads work with default/demo profiles without RabbitMQ. Audit lookup cost grows with audit history because the existing audit indexes are time/actor oriented; a targeted `(target_id, action, created_at)` index can be evaluated against measured larger datasets in a future change. This bounded per-order feature adds no broad report scans or polling.

## Validation and limitations

Backend tests cover persisted timestamp equality, reservations, picking, packing, shipment preparation, dispatch, split warehouses, cancellation retries, missing legacy timestamps, rollback, role restrictions and read-only behavior. Component tests cover loading, forbidden/error recovery, empty history, progress and UTC timestamps. The real Chromium/WebKit E2E flow asserts each lifecycle stage, mobile-width rendering, direct viewer denial and stock invariants. See the PR's exact CI evidence for actual results; adding tests alone is not proof that they pass.

No carrier integration, delivery confirmation, estimated dates, live push updates or full picking-revision history is provided. The timeline's refresh updates its own current snapshot; other fields on an already-open order details panel refresh when that order is reopened. Test fixtures are synthetic and isolated; the suite never contacts the public demo.
