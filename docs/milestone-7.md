# Milestone 7 — analytics and operational reports

## Definitions and time semantics

No revenue, prices or monetary KPIs are inferred from unpriced orders. All metrics come from PostgreSQL. KPI cards are **current/all-time operational state**, independent of the activity date range:

| KPI | Definition |
| --- | --- |
| Total orders | All retained sales orders, including cancelled and shipped |
| Open orders | Current RESERVED, PROCESSING or READY_TO_SHIP orders |
| Cancelled / shipped orders | Current CANCELLED / SHIPPED order counts |
| Awaiting fulfillment | Current RESERVED orders, before warehouse work begins |
| Inventory units | Sum of on_hand across persisted SKU/warehouse balances |
| Reserved / available | Sum of reserved; sum of on_hand − reserved |
| Low-stock combinations | Persisted balances for ACTIVE products and warehouses with available ≤ the requested threshold (default 10, inclusive; 0–1,000,000) |
| Active warehouses / products | Current ACTIVE records; product count does not count each SKU separately |
| Shipments awaiting dispatch | Current READY shipments, not orders; cancelled shipments excluded |

Low-stock monitoring covers **established stocking combinations only**. Missing SKU/warehouse balances are not treated as stocking assignments and are not cross-joined into alerts. Inventory totals include inactive records so retained stock never disappears from totals; low-stock alerts exclude inactive locations/products. Reports display immutable SKU and warehouse codes.

Date inputs are inclusive calendar dates, defaulting to the last 30 calendar days in Europe/Berlin. UTC is also supported. Maximum range: 366 days, years 1900–2100. Backend boundaries are `[start of from-date, start of day after to-date)` in the selected zone; daylight-saving days can be 23 or 25 hours. SQL filters compare timestamptz columns against those instants, and daily buckets use the same zone. Empty days are zero-filled. Timestamp cells and CSV exports use UTC ISO-8601; chart labels are selected-zone calendar dates.

## Charts and activity

- Order volume: orders created per local calendar day, including subsequently cancelled orders.
- Order status: current status of orders created inside the selected range; not reconstructed historical statuses.
- Inventory distribution: current on-hand and reserved units by warehouse; dates do not apply. The largest 12 warehouses by on-hand stock are shown individually; remaining warehouses form a clearly labelled Other group.
- Movement trends (ADMIN/WAREHOUSE_OPERATOR only): daily signed net on-hand and reserved deltas, including receipts, adjustments, reservations, releases and fulfillment. Net changes can be zero despite activity; this is not a count of operations.
- Fulfillment throughput: shipments dispatched per local day based on dispatched_at, not shipment creation date. A multi-warehouse order may contribute several shipments.
- Fulfillment status: current task states for tasks created in the selected range.
- Frequently ordered SKUs: top 10 by distinct non-cancelled orders created in range, with ordered units as supporting data. This measures order frequency, not shipped units; ties use SKU code/UUID.
- Recent activity: latest 10 order creation/cancellation and shipment preparation/dispatch events within the range. Uses business record timestamps; does not expose authentication events, audit details, actors or customer addresses.

Charts use maintained browser SVG primitives through the existing React version, without a third-party charting dependency. Every chart includes an expandable exact-value table and accessible labelling; chart scaling is visual only. Integer aggregates and report values are serialized as strings so large totals retain precision in tables/exports.

## Reports, filtering and authorization

All new endpoints remain protected by sessions, CSRF configuration, password-change requirements and session revocation. Only GET routes are introduced. ADMIN has all reports. WAREHOUSE_OPERATOR and SALES_OPERATOR can read orders, inventory, low-stock, fulfillment and shipment reports, consistent with their existing operational reads. Only ADMIN/WAREHOUSE_OPERATOR can read movement reports. Only ADMIN can read audit reports. Service-level checks also enforce these restrictions before any report or export query.

`GET /api/v1/analytics/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD&zone=Europe/Berlin&threshold=10`

`GET /api/v1/reports/{kind}` and `GET /api/v1/reports/{kind}/export`, where kind is orders, inventory, low-stock, movements, fulfillment, shipments or audit.

Reports support literal query search, status/type/action, warehouseId and skuId where relevant. Unsupported status values and incompatible audit location/SKU filters return structured 400 errors. Paging is zero-based, size 1–100, with stable ID tie-breaking. Orders, tasks, shipments, movements and audit use creation/event date filtering. Inventory and low-stock are current snapshots; dates deliberately do not apply and the UI labels that distinction. Shipments filter by creation date; dispatched throughput separately uses dispatch date.

Report columns deliberately exclude customer names/addresses, user emails, passwords, request bodies and free-text movement reasons/audit detail. The ADMIN-only audit report includes actor UUID/kind, action and target UUID for attribution, not user-profile fields. Export permissions and filters are identical to report permissions/filters.

## CSV and performance

CSV exports query the full filtered dataset, independent of the visible page. They retrieve at most 5,001 rows; more than 5,000 matches produce a structured 422 EXPORT_TOO_LARGE error, with no truncated download. The browser explains the limit and lets the user narrow filters. Cells use RFC-style double-quote escaping and CRLF records. Text beginning with formula-significant characters (=, +, -, @), including after whitespace/BOM/zero-width prefixes, is prefixed with an apostrophe. Leading control characters are also neutralized. Typed numeric columns remain numeric, including negative stock deltas. Export filenames are chosen from the server's report enum, never user input.

Aggregation, filtering, paging and ranking run in SQL with bound values and fixed enum-selected SQL fragments. There are no ORM entity walks or per-result queries. Dashboard/report/export calls run in read-only REPEATABLE READ transactions for internally consistent snapshots; a 5-second PostgreSQL statement timeout limits expensive queries. Snapshot timestamps are returned. Exports use bounded memory rather than an unbounded stream/long transaction. Pages and exports requested at different times may reflect intervening business changes.

V7 adds four indexes for newly introduced query patterns: global movement/task creation dates, non-null shipment dispatch dates and terminal order cancellation dates. Existing ledger indexes begin with warehouse/SKU and cannot efficiently serve global date windows; shipment creation indexes cannot serve dispatch-time throughput. No business tables or data are changed. All-time counts and broad aggregations still require scanning qualifying data; this is appropriate for the project scale, not a claim of constant-time queries. OFFSET paging has the usual large-offset cost. Larger production datasets should be measured with EXPLAIN ANALYZE before adding indexes, keyset pagination or pre-aggregations. Warehouse dropdowns search existing records, and report filters accept exact SKU UUIDs. No fake seed data, scheduled jobs, materialized metrics, new infrastructure or business-state writes are introduced.
