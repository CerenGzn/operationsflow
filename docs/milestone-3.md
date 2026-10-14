# Milestone 3 — inventory and stock reservations

Based on merged main 1a8164a. The existing catalog, warehouse, foundation and
npm ci/package-lock.json workflows are preserved. Inventory uses Spring-managed
transactions and explicit JDBC SQL for lock ordering; JPA remains in catalog and
warehouse CRUD. Both participate in the same PostgreSQL transaction manager.

## Data and consistency

V3 adds inventory_balance keyed by (warehouse_id, sku_id), stock_operation for
idempotency, stock_reservation plus immutable lines, and inventory_movement.
All identifiers reference existing warehouse/SKU records; no inventory fixture
data is seeded. Quantities are integer units, not floating point.

Balances satisfy 0 <= reserved <= on_hand <= 1,000,000,000,000. Available is always
computed as on_hand - reserved; it is never independently persisted or supplied
by a client. Each request quantity is bounded by 1 billion units. Receipts must
be positive; adjustments are signed nonzero deltas and cannot consume reserved
units. Missing warehouse/SKU combinations represent zero stock. Rows are created
on demand inside the write transaction, including their rollback on failure.

Every successful effect appends one movement per balance: RECEIPT (+on_hand),
ADJUSTMENT (signed on_hand), RESERVATION (+reserved), RELEASE (-reserved), or
FULFILLMENT (-on_hand and -reserved). Rows store deltas, resulting balances,
reservation/operation IDs, timestamp, reason and actor. Update/delete triggers
protect the ledger and reservation lines from accidental mutation. Movement
sums reconcile to balance counters, and active reservation lines reconcile to
reserved quantities. The application updates all three representations atomically.

There is no authentication yet. Actor is explicitly UNAUTHENTICATED, never an
unverified user-supplied identity. Reasons and ownership references must use
synthetic business information. This is audit-friendly operational history, not
a claim of authenticated attribution or tamper-proof storage against DB admins.

## Lock order and transaction boundaries

PostgreSQL READ COMMITTED is used. A stock write runs in one transaction:

1. Claim the idempotency key (creation operations), or lock the reservation header
   for release/consumption. Newly created reservation owners are claimed before
   acquiring catalog/warehouse locks.
2. Through the catalog's public StockCatalogAccess boundary, lock all affected
   product rows in ascending PostgreSQL UUID order and verify active status.
   SKU identity/product membership is immutable; SKU availability inherits its
   product's status.
3. Lock all warehouse rows in ascending UUID order and verify active status.
4. Create/lock balances in ascending (warehouse_id, sku_id) order. UUID string
   ordering intentionally matches PostgreSQL UUID ordering, unlike Java UUID's
   signed compareTo implementation. All entry points follow this same order.
5. Validate and update counters, reservation lines/state, movements and cached
   successful response. Commit together; any failure rolls back everything.

Product and warehouse guard locks intentionally serialize more than just a hot
balance. This is a modest-scale monolith trade-off: easy-to-prove deactivation
safety at the cost of throughput across the same product/warehouse. No external
calls occur while locks are held. Requests are limited to 50 distinct lines.
A three-second transaction-local lock timeout bounds contention. Lock failures
return 503 RETRYABLE_CONFLICT; retry the entire request with the same key. There
is no hidden retry inside an aborted transaction and no SKIP LOCKED behavior.

Catalog deactivation now locks its product before checking inventory through a
catalog-owned ProductDeactivationPolicy port. Warehouse deactivation uses its
warehouse guard lock. Both reject while on-hand/reserved stock remains, so a
receipt cannot race a successful deactivation. Zero-stock historical balances
and terminal reservations do not prevent deactivation. All actual inventory
mutations reject inactive product/SKU or warehouse references. A terminal retry
that changes no stock can still return the existing reservation.

## Reservation ownership and Milestone 4 integration

A reservation group has a globally unique, opaque ownerReference and 1–50
explicit warehouse/SKU quantity lines. Duplicate balance lines are rejected.
The owner is immutable and stays reserved after termination. Use an order UUID
as the future owner reference, or a deliberate order-line/fulfillment reference
if Milestone 4 chooses smaller reservation groups. These identifiers currently
are references, not authenticated ownership or foreign keys to nonexistent orders.

ACTIVE -> RELEASED or ACTIVE -> CONSUMED; no reopening. Repeating the same
terminal action returns the existing result without another movement. Requesting
the other terminal action returns 409 INVALID_TRANSITION. All lines terminate
together. No partial release/consumption, TTL, background expiry or warehouse
allocation is implemented. Callers provide explicit allocations; their line
order does not affect lock order or idempotency semantics.

The consume endpoint is an inventory fulfillment primitive, not complete order
or shipment management. Consumption deducts both on-hand and reserved quantities;
release only decrements reserved. Milestone 4 should call InventoryService from
its order application service within the same outer transaction. An outer
transaction rollback is tested. Orders must enforce their lifecycle and lock
order before invoking inventory; do not use HTTP calls between modules or acquire
product/warehouse locks before the documented inventory claim order. For multiple
reservation groups in one transaction, sort group IDs and acquire the union of
stock guard locks consistently; the current API processes one group per call.

## Idempotency

POST receipts, adjustments and reservation creation require UUID Idempotency-Key.
Keys are globally scoped to inventory operations in this single-tenant,
unauthenticated milestone, not to a claimed user. A unique insert with ON CONFLICT
DO NOTHING and a locked lookup coordinates concurrent retries. A SHA-256 digest
covers the typed request; reservation lines are sorted first. The operation type
is also compared. Same key/type/payload replays the original successful response
and 201 status, including the original snapshot. A key reused with different
content/type returns 409 IDEMPOTENCY_CONFLICT. A different key with an existing
reservation owner is rejected by its unique constraint.

The operation row and response commit with the stock changes. Crashes/failures
before commit leave no claim or stock effects; a lost response after commit can
be safely retried. Rolled-back failures are not cached. No retention cleanup is
implemented, so keys persist indefinitely. GET the reservation or balance to
obtain current state rather than assuming a replayed response is current.
Release/consume are inherently idempotent by locked reservation state and do not
require a second key. Exactly-once means one committed stock effect, not exactly
once network delivery. Database unique movement constraints provide another
barrier against duplicate effects.

## REST interface

Base /api/v1/inventory; OpenAPI descriptions are available in Swagger UI.

| Method/path | Body / behavior |
| --- | --- |
| GET /balances | warehouseId/skuId filters; derived available stock |
| GET /movements | warehouseId/skuId filters; newest first |
| GET /movements/{id} | Immutable operation effect |
| POST /receipts | warehouseId, skuId, quantity, reason; Idempotency-Key; 201 + Location |
| POST /adjustments | warehouseId, skuId, delta, reason; Idempotency-Key; 201 + Location |
| POST /reservations | ownerReference, lines[{warehouseId,skuId,quantity}], reason; Idempotency-Key; 201 + Location |
| GET /reservations | Optional ACTIVE/RELEASED/CONSUMED state filter; newest first |
| GET /reservations/{id} | Current group state and immutable lines |
| POST /reservations/{id}/release | reason; 200, safe to repeat |
| POST /reservations/{id}/consume | reason; 200, safe to repeat |

All list endpoints use page >= 0, size 1–100 and stable secondary sort keys.
Validation/malformed requests return 400, missing references 404, insufficient
stock/invalid adjustments/inactive records/ownership or idempotency conflicts
409, and lock contention 503. Errors preserve the existing problem+json contract
without SQL/credential details. Count and page reads use READ COMMITTED and are
not intended as a point-in-time export during concurrent writes.

## UI

Inventory navigation exposes live balances, movement history, and reservations.
Warehouse/SKU selectors filter balances/history and target stock changes.
Catalog reference searches are independent; more than 100 matches asks the user
to refine the query rather than presenting an incomplete dropdown as complete.
The form creates one-line reservations; the API supports up to 50 lines. Release
and consumption require an explicit confirmation and reason. History exposes
truthful actor, reason, operation ID and after-counters.

During an ambiguous network/server failure, the form freezes the submitted
payload and retains its key for Retry same request. Definite 4xx rejections allow
correction. The pending request is retained only while the inventory screen is
mounted; after navigation/reload, inspect movement history before submitting a
new receipt/adjustment. This UI limitation does not weaken backend idempotency
when callers retain their keys. No fabricated counts or live business fixtures
are used.

## Verification

New unit tests cover stock arithmetic and reservation transitions. PostgreSQL
integration tests cover 20 reservations competing for 10 units, simultaneous
releases, reserve/release and release/consume races, concurrent idempotent receipts,
canonical retries, unique owners, reversed multi-line requests, later-line
rollback, outer transaction rollback, invalid adjustments, database constraints,
immutable history, inactive references, deactivation races, history reconciliation,
consumption idempotency and REST validation. Frontend tests cover displayed
quantities, query error/empty/retry, exact-key retries, signed adjustments,
reservation/release workflows, history/filtering and inactive references.

Local verification on 2026-10-14: Maven verify failed before execution because
the wrapper could not reach the configured proxy (curl exit 7). Frontend
typecheck/test/build exited 127 because TypeScript/Vitest dependencies are not
installed. Docker info failed with socket EPERM. Known-blocked npm downloads
were not repeatedly retried. Compose configuration, wrapper shell syntax, POM
XML, package/lockfile consistency, whitespace and sensitive-file checks passed.
See the draft PR for authoritative GitHub Actions results. No successful local
runtime verification is implied by the implementation.

No authentication, orders, payments, brokers, Redis, deployment or real personal
customer records are introduced. Continue using the application locally only.
