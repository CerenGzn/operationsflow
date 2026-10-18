# OperationsFlow architecture

> Current hosting status: the owner now reports a live Render Free / Neon Free demo.
> See [live demo](live-demo.md) and [controlled deployments](controlled-deployments.md).
> Preparation/no-deployment statements below describe the earlier milestone; this
> documentation update does not deploy or change provider settings.

Application implementation through Milestone 7, with Milestone 8 local containerization.
This describes one Spring Boot application, one React application, and one
PostgreSQL database. It does not introduce architecture changes or hosting.

## Source map and boundaries

| Location under `backend/src/main/java/com/operationsflow` | Responsibility |
| --- | --- |
| `catalog` | Products, immutable SKUs, active-catalog access, deactivation port |
| `inventory` | Warehouse metadata, stock balances/ledger, reservations, allocation |
| `customers` | Business customer metadata, versions, deactivation port |
| `orders` | Idempotent creation, immutable snapshots, lifecycle and cancellation |
| `fulfillment` | Warehouse tasks, picks, packing, shipment preparation and dispatch |
| `security` | Sessions, CSRF, identities, roles, bootstrap and account revocation |
| `audit` | Actor-attributed events and administrator-only history |
| `reporting` | SQL aggregation, paginated reports and bounded CSV exports |
| `shared` | API errors, pagination, common status and connectivity contract |

Modules use `api`, `application`, `domain`, and `persistence` subpackages where
needed. The original `identity/package-info.java` is a foundation placeholder;
authentication and user management are implemented in `security`.

Controllers expose validated DTOs. Application services coordinate transactions;
domain types express rules; persistence uses JPA repositories or explicit JDBC.
Orders use customer/inventory application boundaries; fulfillment uses the order
application boundary and inventory consumption primitive. Deactivation ports
avoid exposing another module's repository. These are package conventions within
one Maven artifact, not independently deployed services or compiler-enforced modules.

Reporting deliberately reads operational tables through SQL projections. This
couples report queries to the shared schema while avoiding business mutations
and ORM entity traversal. There is no Kafka, RabbitMQ, Redis, or inter-module HTTP.

## Transactional behavior

- Catalog, customer, and warehouse metadata use JPA and optimistic versions.
  Stock/order/fulfillment persistence uses explicit SQL for predictable locking.
  Both participate in the Spring-managed PostgreSQL transactions.
- Stock balances obey `0 <= reserved <= on_hand`; available stock is derived.
  Ledger effects, counters, reservation state, idempotency responses, and business
  audit events commit together or roll back together.
- Operation/order ownership locks precede catalog/product, warehouse, and balance
  locks. Within those groups, UUID ordering is deterministic. Creation also locks
  the customer. See the detailed operation-specific orders in the milestone guides.
- Allocation prefers the first warehouse by code/UUID that can fill the entire
  order, otherwise splits in that priority. Business priority is distinct from
  database lock order. Insufficient stock rejects the complete transaction.
- Reservation states are ACTIVE → RELEASED or CONSUMED. Repeated terminal requests
  cannot create a second stock effect. Order-owned reservations cannot be changed
  through the manual inventory terminal endpoints.
- Order states progress RESERVED → PROCESSING → READY_TO_SHIP → SHIPPED, with
  cancellation allowed before shipment. Dispatch consumes every allocation in
  one transaction; cancellation competes for the same order lock. No partial shipment.
- API idempotency replays the original successful response for a matching key and
  canonical payload. It does not make network delivery exactly once; clients
  should retrieve current state after a replay when needed.

Product and warehouse guard locks simplify inactive-record and allocation races
but reduce throughput. This is a deliberate modest-scale trade-off, not a claim
of production load certification. Database checks, unique constraints, foreign
keys, and immutable-history triggers supplement service-level rules.

## Security and audit

Spring Security enforces deny-by-default request authorization with ADMIN,
WAREHOUSE_OPERATOR, and SALES_OPERATOR roles. The [security guide](milestone-5.md)
contains the complete matrix; fulfillment/reporting extend those existing scopes.
Only CSRF initialization, login, and health are anonymous; login still requires
CSRF. OpenAPI and audit history require ADMIN.

Passwords use BCrypt cost 12. Cookie-only sessions are HttpOnly, SameSite=Strict,
expire after 30 minutes idle, and are invalidated on logout/password change.
Account security versions revoke stale sessions on their next request. There is
no public signup, default credential, or authentication token in localStorage.
Sessions and bounded login throttling remain process-local. Local HTTP does not
set Secure by default; any future hosting must explicitly configure HTTPS,
Secure cookies, trusted proxies, and operational safeguards.

Business audit records share the operation transaction. Authentication events use
separate short audit transactions. Legacy unauthenticated movements retain their
original attribution. Append-only triggers do not protect against a database owner.

## Frontend and reporting

`frontend/src/app` composes role-aware screens; `features` contains management,
inventory, orders, fulfillment, authentication, users, and analytics. Shared Fetch
clients manage cookies, CSRF headers, structured errors, and session expiry.
React hooks manage requests and abort obsolete reads; TanStack Query is not installed.
Frontend visibility complements, but never replaces, backend permissions.

Native SVG charts include exact-value tables. Dashboard KPIs describe current or
all-time state; activity charts use bounded inclusive calendar date ranges in
Europe/Berlin or UTC, converted to half-open instant boundaries. Reports run SQL
aggregation/filtering in read-only REPEATABLE READ transactions, with statement
limits, bounded pages, and a 5,000-row CSV limit. Audit reports are ADMIN-only;
stock movements are unavailable to SALES_OPERATOR. See [metric definitions](milestone-7.md).

Orders remain UNPRICED. No prices, revenue, invoices, or payments are inferred.

## Database and runtime

Flyway migrations V1–V7 own the schema; migration history is in `public` and
application tables are in `operationsflow`. Hibernate uses `ddl-auto=validate`.
V7 adds reporting indexes. Database connection values come from required
`DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables.

Compose runs PostgreSQL 17.6 with a named volume, readiness check, and loopback-only
published port. Java and Vite run as separate development processes. Vite proxies
`/api` and `/actuator` to the backend; production asset serving is not configured.
The backend defaults to loopback. Actuator exposes health only, with hidden
component details; readiness includes PostgreSQL and liveness does not.

Multi-stage backend/frontend Dockerfiles and `compose.full.yml` now provide a
separate local full-stack workflow. nginx serves production React assets and
proxies same-origin APIs; only its localhost port is published. The original
`compose.yaml` workflow above remains unchanged. See the [Docker guide](full-stack-docker.md)
for isolated volumes, bootstrap, runtime users, HTTP limits and validation.
There is no public deployment pipeline. Maven Wrapper pins
Maven 3.9.11; the backend uses Java 21 / Spring Boot 3.5.7 / springdoc 2.8.14.
The manifests and frontend lockfile are authoritative for dependency versions.
The original [foundation architecture](https://github.com/CerenGzn/operationsflow/blob/4a572c4115fd46fa2a0a2cc34e1313d26dca6a6c/docs/architecture.md)
remains available in Git history; its deferred-feature statements are historical.

## Verification and open work

Surefire runs unit tests; Failsafe runs PostgreSQL `*IT` tests during Maven verify.
Vitest/Testing Library run component and API-client tests in jsdom. GitHub Actions
runs backend, frontend, security audits, and Compose configuration validation.
These are not a complete browser E2E suite, deployment proof, or coverage percentage.
See [verification evidence](verification.md) for the checked run and remaining gaps.

Future work includes hosted-runtime validation, browser E2E coverage, retention
policies, durable sessions if needed, and performance measurement before replacing
coarse locks or OFFSET pagination. These capabilities are not claimed as implemented.

## Prepared single-service demo (Milestone 9)

`Dockerfile.demo` builds React assets into the Spring Boot JAR and runs one non-root
Java process with bounded memory. No hosted resource or live URL exists. The `demo`
profile uses a three-connection direct Neon pool with verified TLS, Secure cookies,
a fixed HTTPS edge contract and ignored forwarding headers. The separate bundled UI
security chain permits only UI GET/HEAD documents/assets; existing operational API
roles, sessions, CSRF, transactions and audit rules are preserved. Local Compose
remains unchanged. A DB-free platform readiness group avoids routine Neon wake-ups;
the full database readiness endpoint remains available for deliberate checks.

See [free demo preparation](free-demo-deployment.md) for conditional feasibility,
public-access limitations, manual reviewer accounts and the deployment approval gate.
