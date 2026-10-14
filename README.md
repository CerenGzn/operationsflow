# operationsflow
B2B order, inventory and fulfillment management platform


## Milestone 1 — Foundation

A Java 21 / Spring Boot modular monolith, React / TypeScript frontend, and
PostgreSQL database. This milestone provides connectivity, schema migration,
health checks, API documentation, and test/CI scaffolding only. Business modules
and authentication are intentionally deferred. Run locally; do not expose this
unauthenticated foundation publicly.

The existing title and description above are preserved. These instructions were
added to make the foundation runnable.

### Prerequisites

- Java 21 JDK; Maven is downloaded by `backend/mvnw` (curl required).
- Node.js 22.12+ (Node 22 LTS recommended) and npm.
- Docker with Docker Compose v2 and a running daemon.
- Network access to Maven Central, npm, and Docker Hub on first setup.

### Run with Docker Compose and local application processes

Compose runs PostgreSQL; Java and Vite run on the host for quick reloads.
This milestone does not provide container images for the application services.
All commands below start in the repository root.

```sh
cp .env.example .env
# Edit .env: use a local-only password and keep both password fields equal.
set -a
. ./.env
set +a
docker compose up -d --wait postgres
cd backend
./mvnw spring-boot:run
```

In a second terminal, from the repository root:

```sh
cd frontend
npm ci
npm run dev
```

Open the frontend at http://localhost:5173. Vite forwards `/api` and `/actuator`
to http://localhost:8080 without broad CORS permissions. Override `BACKEND_URL`
when starting Vite if the backend runs on a different port. This variable is
server-side proxy configuration, not a browser secret.

- Connectivity: http://localhost:8080/api/v1/system
- Database readiness: http://localhost:8080/actuator/health/readiness
- Liveness: http://localhost:8080/actuator/health/liveness
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Swagger UI: http://localhost:8080/swagger-ui.html

Connectivity identifies the API; it is not a substitute for database readiness.
No sample customer records or application credentials are seeded.

Stop Java and Vite with Ctrl-C. `docker compose down` stops PostgreSQL and retains
its named volume. Changing the PostgreSQL password in `.env` does not change an
already-initialized database password. Do not delete the volume unless its data
is disposable.

### Verify

```sh
cd backend
./mvnw test                 # MVC contract unit test; no Docker required
./mvnw verify               # Also packages JAR and runs PostgreSQL Testcontainers
cd ../frontend
npm ci
npm run typecheck
npm test
npm run build
cd ..
docker compose --env-file .env.example config --quiet
```

Integration tests deliberately fail if Docker is unavailable; they never silently
skip. For an explicitly limited build without PostgreSQL integration tests, use
`./mvnw package`; report integration coverage separately. `vite preview` previews
static assets only and has no backend proxy; use `npm run dev` for the connected
foundation UI.

Frontend dependency installation is reproducible through the committed
`frontend/package-lock.json`; use `npm ci` locally and in CI. The merged
foundation was verified by GitHub Actions. Historical cloud restrictions are
recorded in [foundation verification](docs/verification.md).

### macOS setup

Install a Java 21 JDK (for example Temurin), Node 22 LTS, and Docker Desktop with
Compose v2. On Apple Silicon use native ARM64 installations; the PostgreSQL image
supports multiple architectures. Start Docker Desktop before running integration
tests. Colima is an alternative if its Docker socket is correctly configured for
Testcontainers; do not disable the tests to hide socket problems.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
node --version
docker info
docker compose version
```

Then follow the run and verification steps above in your macOS checkout. Ports
5432, 8080, and 5173 must be available. Start with about 4 GB of Docker memory and
increase it if PostgreSQL test containers run out of resources. Preserve the
executable bit on `backend/mvnw`. CI uses Linux, so import filename case must
match exactly. No local MacBook setup was inspected or changed; these steps
remain unverified on macOS.

See [architecture](docs/architecture.md) for package boundaries and dependency
choices. No deployment or publishing workflow is included.


## Milestone 2 — Products, SKUs and warehouses

The workspace now has live product and warehouse management screens. Start the
same PostgreSQL/backend/frontend processes above; Flyway applies V2 automatically.
No business data is seeded. Use synthetic records for development.

Products have a name, description, status and one or more immutable globally
unique SKU codes. Warehouses have immutable unique codes and address metadata.
Both screens support search, status filters, sorting, pagination, creation,
editing, and explicit deactivation. Inactive records are read-only; deactivation
cannot be undone in this milestone. Versions protect against stale edits.

See [Milestone 2 API and design notes](docs/milestone-2.md) for contracts,
constraints, tests and known limitations. Authentication, inventory, orders and
stock reservations remain outside this milestone. Continue to run locally only.


## Milestone 3 — Inventory and reservations

Open **Inventory** in the workspace for warehouse/SKU balances, receipt and
adjustment forms, movement history and reservation management. Flyway V3 applies
on startup. Create synthetic products and warehouses before receiving stock.
Available stock is derived from on-hand minus reserved. Every stock change has
an immutable ledger entry and reason. Product/warehouse deactivation is blocked
while stock remains.

See [inventory contracts and concurrency design](docs/milestone-3.md) for lock
ordering, idempotency, ownership, exactly-once release semantics, and the future
order integration boundary. Reservations are explicit allocations; no order
management or automatic warehouse allocation is included. The consume action
is an inventory-only fulfillment deduction. Authentication is still deferred.

## Milestone 4 — Customers and orders

Open **Customers** to maintain synthetic business accounts and shipping addresses,
then **Orders** to create, inspect and cancel orders. Flyway V4 applies on startup;
existing setup commands and the committed npm lockfile are unchanged. Orders
reserve stock atomically, preferring a single warehouse before splitting by
warehouse code. Cancellation releases all allocations exactly once.

Orders are explicitly **UNPRICED**: the catalog has no price model, so this
milestone calculates unit totals only and does not invent prices or currencies.
Order-owned reservations must be cancelled through Orders; manual inventory
reservations retain their existing release/consume behavior. No order shipment,
authentication or deployment is included.

See [Milestone 4 design, API and verification](docs/milestone-4.md).
