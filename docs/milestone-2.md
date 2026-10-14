# Milestone 2: catalog and warehouses

This milestone follows the user's revised scope: product/SKU and warehouse
management, not authentication. Based on merged main 3b8a3ec. The committed
frontend lockfile and npm ci workflow from main are preserved unchanged.

## Modules and storage

`catalog` owns Product, Sku, repositories, application service and API contracts.
`inventory` owns warehouse metadata only. Neither module accesses the other's
persistence. Controllers validate HTTP DTOs; transactional services orchestrate
persistence; entities enforce lifecycle and version rules. Shared code covers
status, pagination, errors and list-query validation only. No broker or extra
frontend library was introduced. React hooks manage the small screen-local
request lifecycle, including aborting obsolete list requests.

V2 creates product, sku and warehouse tables in operationsflow. UUID keys,
foreign keys, length/format/status constraints, unique codes and timestamps are
explicit. SKU product IDs and codes, and warehouse codes, are additionally
protected against database updates by triggers. Product-to-SKU is one-to-many;
creation requires a first SKU in the same transaction. Duplicate SKU insertion
rolls back the product as well. Existing product identifiers never change.

Product status governs all its SKUs; this milestone has no separate SKU status,
price, inventory or per-SKU attributes. Additional SKU codes may be added while
the product is active. No hard-delete endpoints or reactivation exist.

## REST contracts

Base: /api/v1. OpenAPI/Swagger from the foundation remain available.

| Method and path | Contract |
| --- | --- |
| POST /products | name, description, skuCode; 201 and Location |
| GET /products | Paginated name/SKU search |
| GET /products/{id} | Product details including all SKU codes |
| PUT /products/{id} | name, description, version; 200 |
| POST /products/{id}/skus | code, version; 201 with updated product |
| POST /products/{id}/deactivate | version; 200 with inactive product |
| POST /warehouses | code, name, addressLine, city, postalCode, countryCode; 201 and Location |
| GET /warehouses | Paginated name/code/city search |
| GET /warehouses/{id} | Warehouse details |
| PUT /warehouses/{id} | All editable address/name fields plus version; code excluded |
| POST /warehouses/{id}/deactivate | version; 200 with inactive warehouse |

Lists accept query (maximum 100 characters), status (ACTIVE/INACTIVE or omitted),
page (zero-based), size (1–100, default 20), sort and direction (asc/desc). Product
sort fields: name, createdAt, status. Warehouse sort adds code and city. UUID is
always the final stable tie-breaker. Search uses literal case-insensitive
substrings; percent and underscore are not SQL wildcards. Searches can scan at
larger volumes: use query-plan evidence before adding PostgreSQL trigram indexes.
Page responses expose content, page, size, totalElements and totalPages. Product
list SKU loading is batched into a second query instead of one query per product.

SKU codes: 1–64 characters, uppercase letters/numbers with hyphens/underscores;
warehouse codes: same format, maximum 32. Names are trimmed, nonblank, maximum
160. Descriptions are required strings (empty allowed), maximum 2000. Warehouse
addresses/cities/postal codes are trimmed and required. Country codes have a
strict two-uppercase-letter format; full ISO membership validation is deferred.
Unknown JSON fields are rejected, including attempts to update immutable codes.

Error bodies are application/problem+json. Validation adds code=VALIDATION_FAILED
and errors=[{field,message}]; no rejected values or SQL details are returned.
Malformed payloads/query parameters return 400; missing IDs 404; duplicate codes,
inactive edits and stale versions 409 with stable machine-readable codes.

## Lifecycle and consistency

ACTIVE -> INACTIVE only. First deactivation checks the supplied version; repeated
deactivation of an already inactive record is a successful no-op, even with the
original version. All identifiers stay reserved. Inactive records cannot be
edited or receive additional SKUs. JPA optimistic locking protects races among
updates, SKU additions and deactivation, beyond the initial version comparison.
The UI retrieves fresh details before editing and includes version in writes.
A conflict preserves the user's form; close and reopen to reload before retrying.

There are no inventory/order dependencies yet. Before later milestones permit
stock or fulfillment references, add explicit deactivation eligibility checks
within the appropriate transaction. Do not infer that today's policy covers
future stock or open-order constraints.

The frontend uses only actual HTTP responses for its lists and counts. It has
loading, empty, error/retry and mutation feedback states; permanent identifiers
are read-only after creation; deactivation requires confirmation. Tests use
synthetic fixtures only. Navigation switches between product/warehouse screens;
URL routing/deep links are not added in this milestone.

## Tests and verification

- Domain unit tests: stale writes, idempotent deactivation, inactive edit rules.
- Query unit tests: pagination bounds, allowlisted sorting and stable tie-breaker.
- PostgreSQL/Testcontainers API tests: migrations, create/get/update/deactivate,
  atomic rollback, duplicate codes (including simultaneous creates), immutable
  database identifiers, version conflicts, validation, missing IDs, pagination,
  literal search and status filtering.
- Frontend component tests: loading/error/retry/empty, creation payloads, search
  and filtering, fresh edit/version submission, conflict feedback, warehouse
  metadata and explicit deactivation, inactive read-only state.
- Existing connectivity, Actuator, OpenAPI and migration tests remain; the
  foundation migration assertion now expects version 2.

Local cloud probes still fail: registry requests return EPERM, the Maven wrapper
cannot reach the proxy, and Docker socket connections return EPERM. Maven verify
therefore did not run. npm ci was attempted once and ended with npm exit-handler
failure after blocked downloads; frontend typecheck/test/build exited 127 because
TypeScript and Vitest were unavailable. Runtime verification relies on
GitHub Actions. Local Compose configuration, shell syntax, XML parsing,
lockfile/package consistency and whitespace checks pass. See the draft PR for
actual CI conclusions; no local build/test success is claimed here.

Authentication and authorization are intentionally not implemented. This is a
local development application, not a publicly deployable service. No secrets,
real personal data, deployment steps, orders or reservations are added.
