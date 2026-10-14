# Milestone 5: authentication, authorization and audit attribution

## Security boundary

Spring Security protects every backend request, with deny-by-default routing. Email addresses are canonicalized to lowercase; passwords use BCrypt cost 12 (12+ characters, maximum 72 UTF-8 bytes). There is no public signup, HTTP Basic login, JWT, default credential or password in API responses. The session principal contains only user UUID, role and security version. Password DTOs redact `toString()`; audit records never capture request bodies, passwords, cookies, tokens or submitted login identifiers.

Sessions use cookie-only tracking, HttpOnly and SameSite=Strict, with a 30-minute idle timeout. Login rotates the session ID and CSRF token. Logout and password changes invalidate the session. Each authenticated request rechecks the database account and security version; role changes, edits, deactivation and password resets revoke all existing sessions on their next request. Requests already admitted may finish; revocation does not cancel in-flight transactions.

Clients first GET `/api/v1/auth/csrf`, then send its `headerName`/`token` on every mutation, including login/logout. Tokens use Spring Security's masked representation and live only in frontend memory. Login is POST `/api/v1/auth/login` with email/password; GET `/api/v1/auth/session` restores session state. Cookies use same-origin credentials through Vite's API proxy. No CORS allowance is added. Failed mutations are never automatically retried by the CSRF client. A stale token prompts session rechecking and an explicit user retry.

The bounded in-memory limiter permits 10 attempts per normalized account and 50 per source IP over 15 minutes, counting successful attempts too. Password changes are also limited. It ignores untrusted forwarding headers, hashes limiter identifiers, caps buckets at 10,000 and fails closed at capacity. Unknown-user login performs a dummy BCrypt check. This trades some shared-IP availability for brute-force resistance; blocked accounts receive generic failures and 429 with Retry-After.

## Permission matrix

| Capability | ADMIN | WAREHOUSE_OPERATOR | SALES_OPERATOR |
| --- | --- | --- | --- |
| Product/SKU and warehouse reads | Yes | Yes | Yes |
| Product/SKU and warehouse administration | Yes | No | No |
| Stock balances / availability | Yes | Yes | Yes |
| Movement and reservation history | Yes | Yes | No |
| Receipts, adjustments, manual reserve/release/consume | Yes | Yes | No |
| Customer management and customer directory | Yes | No | Yes |
| Order reads (including fulfillment customer snapshot) | Yes | Yes | Yes |
| Create/cancel orders | Yes | No | Yes |
| Users, role changes, administrative resets | Yes | No | No |
| Audit history and OpenAPI/Swagger | Yes | No | No |
| Own password change/logout | Yes | Yes | Yes |

Only CSRF initialization, login and health endpoints are anonymous. Login still requires CSRF. Other Actuator endpoints are denied. Order cancellation internally releases its reservation within the authorized order transaction; SALES does not gain direct inventory write access. Order-owned reservation restrictions from Milestone 4 remain enforced. Frontend navigation mirrors permissions but is not the security boundary.

## Users and administrator protection

`GET/POST /api/v1/users`, `PUT /api/v1/users/{id}`, `POST /api/v1/users/{id}/reset-password`, and `POST /api/v1/auth/change-password` implement account lifecycle. Listing is paged with email/display-name search. Updates/resets require the current version. Admin-created accounts and administrative password resets require a password change before operational access. Temporary passwords must be shared through a secure out-of-band channel; there is no email delivery or reset-token endpoint. Own password change requires the current password and invalidates sessions.

A singleton database guard serializes bootstrap and administrator-controlled account changes before locking the target user. The caller's current administrative status is rechecked inside that transaction. The final active ADMIN cannot be disabled or demoted, including concurrent attempts. No user deletion endpoint exists. An operator cannot assign roles through operational payloads; unknown JSON fields are rejected.

### First administrator (local development / macOS)

1. Follow the existing README database/environment setup and start PostgreSQL. No user exists after migration.
2. In the same terminal used to run the backend, set `BOOTSTRAP_ADMIN_EMAIL` to a synthetic business email and `BOOTSTRAP_ADMIN_PASSWORD` to a unique password meeting the policy. Enter the password using a hidden terminal prompt (`read -s` in bash/zsh), then export it; never put the literal value in shell history, tracked files or command arguments.
3. Start `./mvnw spring-boot:run` from `backend`. The application creates one administrator only if the user table is empty, recording a SYSTEM bootstrap audit event.
4. Stop the backend, unset both bootstrap variables, and restart normally. Do not retain the password in `.env`. If variables remain configured after initialization, startup fails intentionally. The bootstrap mechanism cannot be used to reset an existing administrator.
5. Start the frontend with `npm ci` and `npm run dev`; sign in using that account. Create operators through Users. Use synthetic accounts and business data only.

If all administrator credentials are lost, recovery requires a separately reviewed, offline database administrative procedure; there is no hidden public bypass. Back up the database securely before any recovery operation.

## Database and audit consistency

Flyway V5 adds `app_user`, `identity_guard`, and `audit_event`; earlier migrations are unchanged. Database constraints enforce unique normalized email, allowed roles, BCrypt hashes and nonnegative versions. Audit actor UUIDs reference retained users. Events are append-only through a database trigger and paged through ADMIN-only `GET /api/v1/audit-events`.

Product/SKU, warehouse, customer, user, order and inventory changes insert audit records in their existing business transaction. A rollback removes both change and audit event. Inventory movement actors now use `user:<UUID>`; historical `UNAUTHENTICATED` movement actors are preserved, with no fabricated backfill. Receipts, adjustments, reservation/release/fulfillment effects and reservation transitions are recorded. Idempotent replays and repeated terminal requests do not add duplicate successful business events. Authentication success/failure, throttling and logout use separate short audit transactions. Failed login is ANONYMOUS, successful authentication is USER, bootstrap is SYSTEM. Detail is limited to controlled role/status changes; customer snapshots and passwords are not duplicated in audits.

Existing stock/order lock ordering and PostgreSQL constraints are unchanged. Security version validation happens before business execution and takes no inventory locks. User changes serialize on the guard, then the user; own password changes lock only that user.

## Verification and limitations

Security integration tests use actual cookie sessions and CSRF tokens, PostgreSQL and the production filter chain. They cover login/logout, cookie flags, role routing, direct access, password hashing/reset, revocation, final-admin races, audit rollback/attribution and legacy history. Existing integration tests use explicit test-only administrators and CSRF fixtures with filters enabled; concurrency workers explicitly propagate that synthetic principal. Existing inventory/order concurrency assertions remain intact. Frontend tests cover session restoration/expiry, role-aware navigation, forced password change, user administration and actual CSRF request transport. CI still runs Maven verify, npm ci, typecheck, tests, build, both npm security audits and Compose validation.

This milestone targets a single backend process. Servlet sessions and limiter buckets are in memory and reset on restart; it is not a multi-replica authentication architecture. Local HTTP uses `SESSION_COOKIE_SECURE=false`. Any future hosted setup must require HTTPS, set `SESSION_COOKIE_SECURE=true`, configure a trusted proxy explicitly and apply equivalent headers at the frontend host. The frontend dev server is not a production hosting setup. MFA, breached-password screening, managed email recovery, centralized throttling, audit retention/export and database-role separation are deferred. The application's database owner can bypass audit triggers; append-only is protection against ordinary application mutations, not a claim of tamper-proof storage. No deployment is included.
