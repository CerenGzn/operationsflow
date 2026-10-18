# Milestone 11 — read-only portfolio reviewers

This implementation adds `DEMO_VIEWER` to the repository. It does not deploy, change
cloud settings, create credentials or modify live users. The owner must approve a
release separately; do not assume the live service already supports this role.

## Permission boundary

Spring Security grants an explicit GET allowlist, not a wildcard read permission.
Existing ADMIN, SALES_OPERATOR and WAREHOUSE_OPERATOR rules remain unchanged.
ReportingService also rejects viewer exports and sensitive report kinds when invoked
directly. There is no broad `/api/**` or `/reports/**` viewer grant. Future routes must be
reviewed against this allowlist, including the product/warehouse ID path patterns.

All paths below are prefixed with `/api/v1`:

| Permitted GET endpoint | Data and limitations |
| --- | --- |
| `/system` | Fixed application name/API version only; no environment/configuration |
| `/products`, `/products/{id}` | Synthetic catalog descriptions, SKU codes and status |
| `/warehouses`, `/warehouses/{id}` | Synthetic warehouse metadata, including location/address |
| `/inventory/balances`, `/inventory/availability?skuId=…` | Units on hand/reserved/available; availability creates no reservation |
| `/analytics/dashboard` | KPIs, dated charts including aggregate movements, order/shipment event summaries; no administrative audit records |
| `/reports/orders` | Order ID/number, status, units and timestamps; no customer snapshot or cancellation reason |
| `/reports/inventory`, `/reports/low-stock` | Warehouse/SKU quantities and status |
| `/reports/movements` | Quantitative ledger history: type, SKU/warehouse, deltas, resulting balances, timestamp; no actor, reason, operation or owner reference |
| `/reports/fulfillment` | Task/order/warehouse identifiers, state, version, timestamps; no destination or tracking reference |

Report pagination/filtering and normal date bounds still apply (maximum 100 rows per
page). Orders and Fulfillment screens render the permitted reports, with date controls,
not the existing operational detail screens. Inventory shows balances; quantitative
history is available under Reports → Movements. This deliberately limits detail rather
than trying to redact sensitive DTOs or search results after querying customer fields.
There is no viewer access to individual order lines/allocations or package detail.

Explicitly denied, including direct HTTP calls:

- Every business POST/PUT/PATCH/DELETE: catalog/SKU/warehouse/customer management;
  receipts, adjustments, reservations, releases/consumption; order creation/cancellation;
  picking, packing, shipment preparation/dispatch; user or role administration.
- All raw `/orders`, `/customers`, `/fulfillment` and inventory movement/reservation
  endpoints. Some return customer addresses, tracking references or free-text reasons.
- Shipment and audit reports, administrative audit endpoints, user lists, Swagger/OpenAPI,
  unknown/future routes and actuator configuration/secret endpoints.
- Every report CSV export, including the five permitted report datasets. The viewer
  UI offers no export action. HEAD on operational routes is not separately granted.

Authentication is required; no anonymous operational access or shared login is added.
Existing public CSRF bootstrap and limited health endpoints remain public. The viewer
can GET its own session, log in/out and change its own password, including the required
initial change. These personal security actions require normal CSRF handling and remain
audited. Thus “read-only” means **no business-data mutations**, not freezing the user's
own credentials, session activity or security audit trail. Password changes cannot
change roles. No authentication, CSRF, audit, concurrency or stock transaction logic
is disabled or altered.

GET business reads were reviewed: query services perform reads; analytics use read-only
transactions and transaction-local query limits; no lazy reservation creation, fulfillment
initialization or audit write is performed by the permitted GET handlers. Session use
may update in-memory expiry/CSRF state. Explicit full readiness checks can wake Neon;
this is a resource side effect, not a business-data mutation.

## Owner account workflow (after approved deployment)

1. Confirm the selected release passed all CI jobs and V8 applied successfully. Use
   the [controlled deployment procedure](controlled-deployments.md); no release is
   authorized by this document. Keep the existing administrator and bootstrap values
   unchanged/removed. Do not bootstrap an initialized database again.
2. Sign in as the owner ADMIN. In Users → New user, enter an individually assigned
   synthetic email-format alias and non-personal display name. Explicitly select
   `DEMO_VIEWER` (the existing default role is unchanged).
3. Choose a unique temporary password using the existing password policy and share
   it privately with that reviewer. Do not put credentials in the repository, PR,
   README, recordings or public messages. No account is created automatically.
4. The reviewer signs in, changes the temporary password and signs in again. Check
   that the read-only demo indicator appears and permitted screens load. User/customer
   administration and business write controls must be absent; backend denials apply
   regardless of browser controls. Do not test destructive calls against live data.
5. Agree an end time. After review, edit that account in Users, set Inactive and save.
   Existing sessions are rejected on their next request. No automatic expiry or
   scheduled deactivation is implemented. Use the existing administrative password
   reset only when necessary; it also revokes sessions and forces another change.

## Database and rollback

`V8__demo_viewer_role.sql` transactionally replaces V5's PostgreSQL
`app_user_role_check` with the original three roles plus DEMO_VIEWER. It makes no data
updates, inserts or account provisioning. Existing role values, password hashes,
security versions, audit and operational rows are preserved. The ALTER briefly locks
`app_user` and validates existing rows; this small demo table should be short-lived,
but lock contention must be considered in a future approved release. Do not reset Neon.

The schema expansion is additive; **binary rollback is not transparent after viewer
accounts exist**. Older Java enums cannot deserialize DEMO_VIEWER even for inactive
rows. Deactivation alone is insufficient. Prefer a reviewed forward fix. If rollback
is necessary, while running the newer version, the owner must deactivate every viewer
and reassign every viewer row to an older supported role using authorized administration;
keep them inactive to avoid accidentally granting operator privileges. Confirm no
DEMO_VIEWER rows remain before starting an older binary. No such changes are performed
by this milestone. Retain V8 history; never edit applied migrations. An older binary's
Flyway validation policy must also be checked for the newer schema version before a
separately approved rollback. A code rollback does not roll back business data.

## Verification and limitations

New tests use PostgreSQL and real Spring Security filters. They authenticate actual
viewer accounts, exercise every permitted business GET, enumerate every mapped
non-auth mutation with valid CSRF and require 403, deny sensitive reads/exports,
compare all application-table contents before/after reads and denied writes, verify
private fixture fields do not leak, and test initial password change, logout, user
creation, escalation denial and deactivation. Service-level report tests enforce
restrictions independently of HTTP. A V7→V8 migration test checks existing users are
byte-for-byte equivalent and invalid role values remain rejected. Existing role,
concurrency and container suites remain enabled. Frontend tests cover read-only
navigation/actions, safe report routing, filters/pagination, permission errors and
admin selection of DEMO_VIEWER.

Local Maven verification failed connecting to the configured proxy; `npm ci` failed
with EPERM connecting through the proxy; the managed Docker socket returned operation
not permitted. No repeated retries or weakened tests. GitHub Actions is the execution
source for this branch; consult its PR checks for actual results. Static and Compose
configuration validation do not substitute for runtime tests.

This is a synthetic-data demo, not a PII anonymization layer: permitted product/warehouse
free text and identifiers must contain only safe synthetic content. No automatic PII
classification, per-reviewer data isolation or prevention of screenshots/scraping is
implemented. Denying CSV prevents the export facility, not copying paginated data.
Large repeated reads consume shared free-tier resources; no new read rate limiter is
added. Existing session/throttle/free-hosting limitations still apply. No production
security audit or comprehensive live browser E2E coverage is claimed.
