# Free demo deployment preparation (Milestone 9)

**Prepared, not deployed.** No account, resource, production secret, billing setup,
public URL or live demo was created. Do not apply `deploy/render-free.yaml` yet.
Review this PR and its CI, then obtain separate deployment approval. Creating a
Render service triggers an initial deployment even when automatic deploys are off.

## Verdict and evidence

**Conditionally plausible for an occasional, single-reviewer demo; reliable €0
hosting is not proven.** Provider documentation checked on 2026-10-18:

| Constraint | Evidence and consequence |
| --- | --- |
| Render Free compute | [Compute plans](https://render.com/docs/compute-plans): 512 MB RAM, 0.1 CPU. Java startup, BCrypt and analytics compete for a very small CPU allocation. |
| Render idle/restart | [Free services](https://render.com/docs/free): idle suspension after 15 minutes; wake-up around one minute, plus application/database startup. Restarts are possible; local files are ephemeral. |
| Render quotas/cost | Same source: 750 free instance hours/workspace/month. Bandwidth/build overages can incur charges with a payment method; without one, services/builds are suspended instead. External database traffic can also trigger free-service suspension. |
| Neon Free | [Pricing](https://neon.com/pricing): $0, no card or time limit advertised; 100 CU-hours/project/month, 1 GB storage/project, up to 2 CU and 5 GB public transfer/project included. Verify dashboard limits before creating anything. |
| Neon inactivity | [Scale to zero](https://neon.com/docs/introduction/scale-to-zero): wake-up adds connection latency. Free scale-to-zero is not an availability guarantee. |
| Public ingress | [Render web services](https://render.com/docs/web-services): one public endpoint, managed HTTPS termination and HTTP-to-HTTPS redirect; container port is internal. This is not a private service. |

Free-tier terms can change. Do not accept a paid plan, a trial that converts to paid,
a payment-method requirement, or an upgrade to overcome limits. If the account
already has billing enabled, do not assume this configuration caps workspace costs:
stop and review billing separately. Strict €0 means accepting suspension/unavailability.
No autoscaling, paid disks, workers, custom domains, previews or provider databases
are declared. If Render requests card verification, stop; a no-card path is not
guaranteed for every account. Neon Free is not the expiring Render Postgres product.

### Resource budget and acceptance gate

`Dockerfile.demo` builds React once and copies its production assets into Spring
Boot's classpath. Only one Java process runs; no Node/nginx at runtime. It starts
with 64 MiB heap and caps heap at 224 MiB, metaspace at 128 MiB, code cache at 32 MiB
and direct buffers at 16 MiB. Thread stacks are 512 KiB; Serial GC and one logical
processor reduce JVM overhead. These caps total 400 MiB **before** stacks, JVM/native
libraries, libc, file cache and other allocations. They do not guarantee RSS below
512 MB. Twenty Tomcat workers, a three-connection pool and small synthetic datasets
are deliberate demo limits. No production capacity or startup-duration claim is made.

CI adds a real image smoke run with `--memory=512m --memory-swap=512m --cpus=0.1`.
It measures readiness time and tests compiled assets, CSRF/login, database reads,
restart persistence and session loss against local PostgreSQL. This does not model
Render scheduling, Neon latency, TLS, migrations on a remote database or browser
rendering. Before any public release, require that CI passes and then, in a separately
approved evaluation, measure repeated cold starts, memory during CSV/report workloads,
and interactive latency. Aim for headroom rather than merely avoiding one OOM.
If startup repeatedly fails, RSS approaches the limit, or login is unusably slow,
stop the free-hosting attempt; do not silently upgrade or weaken security.

## Architecture and boundaries

```text
Safari -> Render HTTPS edge -> single Spring Boot container :PORT
                             |-- React index.html + /assets/*
                             |-- existing /api/v1/* and security
                             +-- TLS-verified direct Neon PostgreSQL
```

`Dockerfile.demo` uses Node/npm ci and Java/Maven Wrapper build stages, then a
non-root Java 21 JRE runtime. Root `.dockerignore` allowlists only source/build
inputs. No environment file, credentials or browser-secret build arguments are
included. Version tags remain mutable; review image scans/digests before deployment.
Existing `compose.yaml`, `compose.full.yml`, and both local Dockerfiles are unchanged.

The `demo` profile enables bundled UI and deployment settings. The `bundled-ui`
profile enables only static/UI routing for local image tests. UI GET/HEAD requests
use a separate, stateless security chain; API sessions/CSRF/roles are unchanged.
Explicit fallback routes serve the shell for existing screen names. Missing API
routes never receive SPA HTML. The frontend currently selects screens in React
state; a `/orders` refresh loads the shell but does not implement new deep-link state.
Only static assets and the login shell are anonymous; no new anonymous write access.

## Database and health

Use Neon's **direct** endpoint (hostname without `-pooler`) and PostgreSQL 17 where
available. The three-connection Hikari pool is sufficient for the intended small
single-instance demo. Flyway uses the same direct connection configuration, avoiding
transaction-pooler/session advisory-lock compatibility questions. Neon offers
[PgBouncer pooling](https://neon.com/docs/connect/connection-pooling), but it adds
no needed benefit at this scale and is intentionally rejected by the demo validator.
A pooled runtime/direct migration split would require separate testing later.

The JDBC URL is constructed from validated `DEMO_DB_HOST` and `DEMO_DB_NAME`, never
a browser variable. It uses `sslmode=verify-full` and
`org.postgresql.ssl.DefaultJavaSSLFactory` for the JRE CA trust store, with hostname
verification. See [pgJDBC TLS](https://jdbc.postgresql.org/documentation/ssl/).
Do not disable TLS/certificate checking. Username/password remain separate environment
values. The demo validator rejects query-string injection, pooled hosts, JDBC URL
overrides and insecure cookie/proxy settings before datasource initialization.

Pool settings: maximum 3, minimum idle 0, idle timeout 60s, max lifetime 5m,
keepalive disabled, acquisition timeout 30s, validation timeout 5s. JDBC connection
timeout is 15s and socket timeout 60s; Flyway gets three connection retries. A wake-up
may still exceed a timeout. Hikari can replace broken connections; stock/order writes
are never automatically retried. After ambiguous failures use existing idempotency
keys and retrieve current state. Migrations and transactional rules are unchanged.

Render probes `/actuator/health/demo`, which checks application readiness state only.
This avoids querying/waking Neon on every provider health poll. Flyway/JPA must still
finish before initial readiness. `/actuator/health/readiness` remains the explicit
full database check and returns 503 if unavailable. Check it manually after wake-up
and before a review; do not run periodic uptime bots or database readiness polling.
A later database outage does not make the cheap platform probe fail: API operations
can fail while Render still considers the process healthy. This is intentional.

## Environment variables (names, never real values)

| Name | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `demo` for the prepared hosted image |
| `PORT` | Render-provided HTTP listen port; default 10000 |
| `DEMO_HTTPS_EDGE` | Explicit `true` acknowledgement that only Render's HTTPS-enforcing edge can reach this connector |
| `DEMO_DB_HOST` | Direct Neon hostname only; no scheme, port, credentials or query |
| `DEMO_DB_NAME` | Database name: letters/digits/underscore, 1–63 characters |
| `DB_USERNAME`, `DB_PASSWORD` | Neon database role and password, set privately in provider environment UI |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | Temporary first-start values only; remove immediately after successful initialization |

Do not add `DB_URL`, forwarding overrides, `SESSION_COOKIE_SECURE=false`, Vite secret
variables or credential-bearing `JAVA_TOOL_OPTIONS`. The profile sets Secure cookies
unconditionally and guards its TLS JDBC URL. No GitHub secrets are required.

## Public-demo security and reviewer access

Render is the sole trusted TLS edge for this profile. We do **not** trust arbitrary
`Forwarded` or `X-Forwarded-*` headers. Tomcat's connector uses a fixed external HTTPS
scheme/secure flag/443 proxy port; the internal connection is HTTP. This ensures
Secure cookies, HSTS and secure logout behavior without IP/header spoofing. Never
expose that connector directly on a public host or reuse this profile behind an
unreviewed proxy. Validate the provider's HTTPS redirect before sharing the URL.

Sessions remain HttpOnly, SameSite=Strict and expire after 30 idle minutes. Restart,
redeploy and idle suspension lose sessions and reset throttling; users sign in again.
No durable sessions, MFA, distributed rate limiting, or per-reviewer data isolation
is added. Account and IP throttles still apply. Because forwarded client IP is not
trusted, the IP limiter may group visitors behind the edge; this is a documented
availability trade-off, not a relaxation of login protection. Public CSRF requests
can allocate sessions; internet abuse may exhaust memory or quotas. Treat suspected
abuse as a reason to suspend the demo, not to raise a paid limit.

No public signup or shared/password-in-README demo account. The owner manually creates
an individual synthetic reviewer account using the existing administrator UI, shares
its temporary password privately, and uses SALES_OPERATOR or WAREHOUSE_OPERATOR only
as needed. Those roles **can modify** synthetic operational data; they are not read-only.
Require password change on first login, deactivate after review, and give ADMIN to
no external reviewer. If shared-data changes are unacceptable, demonstrate by screen
sharing instead. Synthetic customer names/emails use fictional businesses and
`example.invalid`; do not import production records. Populate a few products,
warehouses, receipts and orders through the authenticated UI, with normal audit
attribution. No seed scripts or automatic business-data writes are introduced.

## Future browser-only setup — owner actions, after separate approval

1. Review and merge the PR manually only after required CI and review. Recheck the
   linked free-tier terms and the acceptance gate above. No local developer tools
   or personal-file permissions are necessary for the provider setup.
2. Sign up/log in to Neon in the browser. Select **Free**, not a paid plan or trial;
   do not add a payment method. Create one PostgreSQL project in a region near
   Render Frankfurt (prefer Frankfurt if offered). Keep auto-suspend enabled,
   choose the smallest free compute range suitable for the demo, and verify the
   actual project quotas. Do not enable optional paid integrations or features.
3. In Neon Connect, disable pooling, select the intended database/role and obtain
   host/database/user/password privately. Never paste a connection URL into chat,
   GitHub, a screenshot or logs. Keep only the required provider environment values.
4. Sign up/log in to Render with a free workspace and no payment method. Install its
   GitHub integration with **Only select repositories: CerenGzn/operationsflow**.
   Do not authorize all repositories or change repository visibility. If narrower
   access or a no-payment setup is unavailable, stop.
5. Choose New Web Service, Docker, this repository, reviewed `main`, region Frankfurt,
   repository-root build context, `Dockerfile.demo`, **Free** compute. Use the
   provider-generated domain; add no disk/database/worker. Turn automatic deployment
   **Off**, previews off, and set health path `/actuator/health/demo`. Leave the Docker
   start command unset so the image's memory-limited entrypoint is used. The file
   `deploy/render-free.yaml` is a reference, not a command to apply now.
6. Privately configure the environment names above. For the first empty database
   only, set both bootstrap variables using a unique administrator password (12–72
   UTF-8 bytes) and a synthetic email. No known/default password exists. Verify the
   plan summary shows Free/$0 and no paid additions **before** clicking Create.
7. **Create triggers a real deployment. This is the approval boundary, not part of
   Milestone 9.** After future approval, watch build/startup privately. On healthy
   startup, immediately delete both bootstrap variables and save/redeploy. Verify
   the next start succeeds and the original administrator can sign in. Leaving
   bootstrap values set intentionally makes subsequent starts fail. Free Render has
   no shell/one-off job workflow; use the environment UI. Do not add a bootstrap API.
8. Complete the checklist below, populate a tiny synthetic dataset, then manually
   invite one reviewer. Record actual startup/memory results before sharing.
   Keep auto-deploy off; future updates are explicitly reviewed manual deployments.

## Validation and troubleshooting

Before future deployment, CI must pass existing backend PostgreSQL/security/concurrency
tests, frontend tests/build/audits, local Compose smoke and the new bundled-image job.
Targeted additions cover static delivery, API exclusion, CSRF, readiness, Secure cookies,
TLS/proxy validation and pool settings. Exact local commands if a supported environment
is available:

```sh
(cd backend && ./mvnw --batch-mode --no-transfer-progress verify)
(cd frontend && npm ci && npm run typecheck && npm test && npm run build)
(cd frontend && npm audit --json && npm audit --omit=dev --json)
docker build -f Dockerfile.demo -t operationsflow-demo:ci .
python3 scripts/bundled-image-smoke.py
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example -f compose.full.yml config --quiet
git diff --check
```

The smoke script uses random disposable credentials and deletes only its own CI
containers/network. It uses local PostgreSQL with `bundled-ui`, so does not claim
Neon TLS or Render HTTPS testing. Fixture resources in Java tests are also explicitly
not the actual production bundle; the image job validates the real Vite artifacts.

Future hosted checks:

- HTTP redirects to HTTPS; certificate valid; no mixed content; no unencrypted public backend.
- Login shell/assets load in Safari; explicit SPA routes load without masking API failures.
- Cookie has Secure/HttpOnly/SameSite=Strict; no token in localStorage; logout invalidates it.
- Missing CSRF fails; anonymous APIs fail; operators cannot use ADMIN endpoints; audit actor correct.
- Full DB readiness succeeds after Neon wake-up, migrations validate, and synthetic writes persist.
- Sleep/restart requires login, retains database records and works without bootstrap variables.
- Repeat cold start and small reports/exports while observing memory/CPU and both providers' quotas.

TLS failure: verify direct hostname and CA trust, never weaken `sslmode`. Startup
failure: check missing variables, already-used bootstrap, memory and migration errors
privately. Pool timeout: wait for wake-up, refresh reads, inspect operation state before
retrying writes. Render loading page: allow cold start; do not promise immediate access.
OOM or very slow BCrypt: stop the free demo evaluation and document the evidence.

## Cost containment, shutdown and alternatives

Check both providers' usage pages before each review. Limits are shared with other
resources in the relevant workspace/project. Do not add billing or upgrade to solve
suspension; no configuration file can guarantee €0 for an already-billable account.
A paid account can incur charges even when the web service's instance is Free.

To stop exposure, suspend/delete the Render service in its dashboard and confirm
it is unavailable. To remove the setup completely, delete the Neon project after
confirming its synthetic data is disposable, revoke the Render GitHub integration
if no longer needed, and check both dashboards for remaining resources/billing.
Deleting provider resources does not delete this repository. Do not rely on short
free restore windows as backups; avoid keeping anything irreplaceable in the demo.

If Render's resource/verification constraints fail, the safest €0 alternative is
the existing complete Docker stack in a supported cloud development workspace,
shown in a supervised review without publishing credentials. Browser-only workspace
availability and included hours depend on the owner's account; it is not promised
as a permanently free public host. Another option is a portfolio walkthrough/video.
Splitting React onto a free static host does not solve Java memory/CPU constraints
and adds cookie/CORS complexity. No alternative perpetual free public Java service
is asserted without a separate current assessment.
