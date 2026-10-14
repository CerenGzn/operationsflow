# Foundation architecture

One backend Maven module, one frontend application, one PostgreSQL database.
The backend root package is `com.operationsflow`. Business package boundaries
are declared for identity, catalog, customers, inventory, orders, fulfillment,
reporting, and audit; they contain no business implementation yet. Add API,
application, domain, and persistence subpackages as each module gains behavior.
Do not access another module's repositories directly.

`shared/api/SystemController` supplies a small versioned connectivity contract.
The frontend separates app composition from its HTTP client. A cancellable fetch
is enough for one foundation request; TanStack Query will be introduced when
business server state arrives. Spring Security is deferred to Milestone 2 rather
than adding temporary users or unfinished authorization.

Spring Boot 3.5.7 manages compatible JPA, Flyway, PostgreSQL driver, JUnit, and
Testcontainers versions. Springdoc 2.8.14 targets Boot 3.x. Java 21, Maven 3.9.11,
Maven Wrapper 3.3.2, React 19.1.1, TypeScript 5.9.3, and Vite 7.1.9 are fixed stable
releases, not floating latest tags. Their downloads and combined build could not
be verified in this environment; no claim of latest security patch status is
made. Recheck patch releases when dependency access is available.

The POSIX `mvnw` launcher downloads the official Apache Maven Wrapper JAR over
HTTPS, then delegates distribution handling to MavenWrapperMain. Its properties
pin both wrapper and Maven versions. The downloaded JAR is ignored. Java and
curl are required; Linux and macOS are supported, not Windows batch execution.

V1 creates only the `operationsflow` schema. Flyway's history is in `public`,
and Hibernate uses `operationsflow` with `ddl-auto=validate`; there are no
placeholder business tables. Later migrations are additive. Flyway runs before
JPA initialization. All database connection settings are required environment
variables; there is no fallback password in application configuration.

Compose provides PostgreSQL with persistent storage, a readiness check, and a
loopback-only published port. Backend/frontend run on the host. This avoids
adding image builds and container proxy setup before business development.
Database and Testcontainers use the same PostgreSQL image version.

The backend binds to loopback by default (override with `SERVER_ADDRESS` only
when deliberately configuring a private runtime). Only health is exposed through Actuator; detailed components are hidden.
Readiness includes the database; liveness does not. OpenAPI documents the custom
endpoint. There is no authentication yet, so this milestone is for local use.

Surefire runs the HTTP slice test; Failsafe runs `*IT` tests during `verify`.
PostgreSQL tests check migration validity/repeatability, database readiness,
OpenAPI generation, and restricted Actuator exposure. Frontend tests cover
loading, success, HTTP failure, malformed JSON shape, and network failure.
GitHub Actions runs independent backend, frontend, and Compose checks without
publishing artifacts or deploying anything.
