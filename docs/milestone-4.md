# Milestone 4: Customers and orders

## Scope and boundaries

Adds `customers/{api,application,domain,persistence}` and
`orders/{api,application,domain,persistence}` in the existing modular monolith.
Customer metadata uses JPA with optimistic versions. Order persistence uses
explicit JDBC for locks, immutable snapshots and joined projections. Both use the
same Spring transaction manager and PostgreSQL connection as inventory.

Orders call the customer application service and inventory's public
`OrderInventoryAccess`/`InventoryService` boundaries, never their repositories or
HTTP endpoints. Inventory obtains catalog snapshots through `StockCatalogAccess`.
Customer deactivation depends on its own `CustomerDeactivationPolicy` port,
implemented by orders, avoiding a service dependency cycle. Frontend screens use
the real `/api/v1` APIs through the existing Vite proxy. No dependency, lockfile,
CI installation, infrastructure or authentication changes are needed.

## Pricing decision

The catalog has no sale prices, currency, taxes or monetary precision rules.
Pricing is explicitly deferred rather than inventing zero prices. Every order
response has `pricingStatus: UNPRICED`, `totalAmount: null`, `currency: null` and
server-calculated `totalUnits`. The UI identifies this limitation prominently.
These are stock commitments, not invoices or payable orders. A future pricing
milestone must introduce versioned price sources and snapshot line unit prices,
currency, rounding and totals. Existing unpriced orders must not be silently
repriced. No monetary calculations use floating point.

## API

All bodies use JSON and UUID identifiers. Validation failures return 400 problem
details with field errors. Missing records return 404; inactive records, stock
shortages, stale versions, duplicate identifiers and idempotency conflicts return
409. Lock contention returns 503 `RETRYABLE_CONFLICT`; retry the same request/key.
Generated OpenAPI is available at `/v3/api-docs` and `/swagger-ui.html`.

| Endpoint | Behavior |
| --- | --- |
| POST `/customers` | 201, unique immutable uppercase `referenceNumber`, name and shipping address |
| GET `/customers` | Search name/reference/city; status; page/size; sort/direction |
| GET `/customers/{id}` | Current customer metadata/version |
| PUT `/customers/{id}` | Edit active name/address with expected `version` |
| POST `/customers/{id}/deactivate` | Expected version; open orders block deactivation; repeat is safe |
| POST `/orders` | 201 and Location; required UUID `Idempotency-Key`; customerId and 1–50 distinct SKU lines |
| GET `/orders` | Search order number/customer snapshot name/reference; customerId/status; page/size; sort/direction |
| GET `/orders/{id}` | Immutable customer, shipping, product/SKU and allocation snapshots; current state |
| POST `/orders/{id}/cancel` | Required reason, up to 500 characters; 200, atomically releases reservation |

Paths above are relative to `/api/v1`. Quantities are whole units 1–1,000,000,000
per SKU. Duplicate SKU lines are rejected; the client must combine them. Unknown
or inactive customer/product references cannot create an order. Only active
warehouses participate; stock in an inactive warehouse is excluded. No order
update/delete/ship API exists. Customer references, order lines and allocation
snapshots cannot be edited through the API. Synthetic business data only.

Customer pagination is zero-based, size 1–100; sorts: name, referenceNumber, city,
createdAt, status. Orders use createdAt (default descending), orderNumber,
customerName, status with UUID tie-breaks. Search is a literal case-insensitive
substring, not a SQL wildcard expression. Frontend reference selectors show up
to 100 search matches and explain how to refine the search; order lists paginate.

## Allocation and transaction invariants

1. Claim the order idempotency key and hash the canonical customer/lines request.
   SKU lines are sorted by UUID string before hashing, so input order is irrelevant.
2. Lock the active customer, then insert an order-owned reservation header.
3. Lock distinct product rows in PostgreSQL UUID order, validating active SKUs;
   capture SKU code/product name under these locks.
4. Lock active warehouses in UUID order, then existing balances in
   `(warehouse_id, sku_id)` UUID order. Missing balances mean zero stock. Product
   locks also block concurrent receipts that could create new matching balances.
5. Business allocation priority is immutable warehouse **code ascending**, then
   UUID. Prefer the first warehouse able to cover the **entire order**. If none
   can, split each requested SKU across warehouses in that priority, taking the
   minimum of available and remaining demand. This is deterministic, not a
   distance/cost/bin-packing optimizer. It is independent of physical lock order.
6. If any SKU cannot be fully covered, roll back everything. Otherwise insert
   reservation lines and ledger deltas, update balances, insert order snapshots,
   and cache the response, all in one transaction.

Only the successful commit publishes these effects. Failed requests leave no
order, reservation, ledger changes or idempotency claim. Sequence-based order
numbers are unique and monotonic by sequence allocation, not gapless or ordered
by commit time; rollbacks can leave gaps. `totalUnits` is derived server-side.
Database checks retain `0 <= reserved <= on_hand`; unique keys and foreign keys
protect identity and references. V4 adds immutable customer reference/order
snapshot triggers, fixed cancellation transition/version checks and immutable
reservation ownership. Application transactions enforce cross-table quantity
reconciliation; direct SQL administration must not bypass those services.

Lock order is operation/order row → customer (creation only) → reservation header
→ product IDs → warehouse IDs → balance pairs. UUID **string** sorting matches
PostgreSQL UUID order (Java UUID.compareTo has signed semantics). A three-second
lock timeout bounds waits. The implementation deliberately locks all active
warehouses while allocating and relevant products while changing stock. This
limits allocation throughput but makes consistency/deactivation races tractable
at portfolio-project scale. Optimize only after measuring; finer locking needs
new concurrency tests and a documented replacement strategy.

## Ownership, cancellation and future fulfillment

An order owns exactly one reservation group, potentially with multiple warehouse
allocations. `stock_reservation.order_id` is unique; `sales_order.reservation_id`
is unique. A deferred FK allows inserting the reservation header before stock
locks and inserting its owning order later in the same transaction. Neither can
commit without the referenced row. `order:<UUID>` is reserved for this internal
ownership model. Manual reservations cannot claim this prefix.

Current transition: `RESERVED → CANCELLED`. Cancellation locks the order, then
its reservation, then the same product/warehouse/balance order. Each allocation
gets one release movement. On-hand remains unchanged. Repeated/concurrent
cancellations return the cancelled order and preserve the first reason/version;
there is no second release. Cancellation failure rolls back both order state and
stock effects. Direct inventory release/consume rejects **all** order-owned
reservations, including terminal ones, with `ORDER_MANAGED`; the UI shows
“Managed through Orders” instead of those buttons.

`PROCESSING`, `PARTIALLY_SHIPPED`, `SHIPPED` are future domain vocabulary only;
current APIs and database transitions cannot enter them. Future shipment logic
must lock order → reservation → stock, deduct on-hand and reserved atomically,
and define partial quantities and competing cancel/ship transitions. The current
manual inventory consume primitive is not exposed as order fulfillment.

Customer deactivation holds the same customer lock used by creation and rejects
open orders. Cancellation does not acquire the customer lock. Product/warehouse
deactivation retains Milestone 3's stock safeguards. Out-of-band status corruption
can prevent cancellation; it fails closed instead of mutating inactive stock.

## Idempotency and client retries

Order creation keys have their own namespace/table and no expiry in this
milestone. Matching keys/payloads replay the original 201 body even if the order
was subsequently cancelled; GET returns current state. Different payloads with
the same key return 409. Failed transactions may retry the same key after a stock
receipt or other correction. Keys are not authentication or tenant boundaries.

The UI creates one key per submission, disables duplicate submission and persists
the exact key/body in tab-scoped session storage **before** sending. Ambiguous
network/5xx errors retain it across navigation/reload; the retry resends unchanged
payload and key. Definitive 4xx errors clear the pending attempt and preserve
form lines for correction. Session storage must work to submit. Closing the tab,
clearing browser storage or submitting independently in another tab is not
cross-tab business deduplication. Use GET/list to reconcile before a new request
if the saved key is lost. Cancellation is idempotent by order ID without a new key.

## Verification and limits

Added PostgreSQL/Testcontainers scenarios cover customer validation/versioning,
snapshot stability, single/split/multi-SKU allocation, insufficient-stock and
outer-transaction rollback, concurrent oversell attempts using different
customers, concurrent/reordered idempotent creation, exactly-once cancellation,
create/cancel and customer deactivation races, ownership bypass prevention,
inactive references, database constraints, search and structured HTTP errors.
Unit tests cover deterministic allocation and permitted/forbidden transitions.
Frontend tests cover customer forms, order states, actual request contracts,
network retry across remount, stock rejection, filters and confirmed cancellation.
Existing inventory/catalog/foundation tests remain enabled.

Run the README verification commands locally or use the unchanged GitHub Actions
workflow (`./mvnw verify`, `npm ci`, typecheck, tests, production build and Compose
configuration validation). Cloud shell Maven/npm networking and Docker socket
access were blocked in this session; those runtime checks require CI. Static
Compose validation and diff checks run locally. CI results are recorded in the PR.
No successful local runtime execution is claimed. macOS setup remains the README's
Java 21/Node 22/Docker procedure and was not accessed or tested.

No authentication, real customer data, order payments, shipments, public hosting
or deployment. Ledger actor remains `UNAUTHENTICATED`. Run in a trusted local
workspace only. No secrets are added.
