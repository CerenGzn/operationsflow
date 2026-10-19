# Real-browser end-to-end tests

The isolated `Browser E2E` workflow exercises the actual React build through nginx,
Spring Security and Spring Boot against disposable PostgreSQL 17. No application API
is mocked. It never targets Render, Neon or the public demo. These tests are distinct
from the owner's historical live manual smoke tests; they do not prove live health,
production readiness, load capacity or exhaustive browser coverage.

## Coverage

Each browser runs one cohesive lifecycle scenario with explicit named steps:

- Administrator UI login, reload/session persistence, HttpOnly/SameSite cookie
  attributes, missing-CSRF rejection, logout and subsequent 401/reload behavior.
- Product/SKU, warehouse and customer creation through real forms.
- Receipt of 100 units; on-hand/reserved/available assertions in the inventory table.
- Order for 20 units: 100/20/80, cancellation: 100/0/100.
- Second order for 30 units, picking, verified packing and shipment preparation.
- Individually generated DEMO_VIEWER, mandatory initial password change, real login,
  permitted navigation and direct GET permission checks. The account is created via
  the actual admin API as fixture setup; business lifecycle mutations use the UI.
- Hidden write/admin controls and direct requests carrying valid CSRF tokens denied
  with 403: create/edit/cancel/dispatch, pick/pack/shipment, receipt and deactivation.
  The tested order is genuinely dispatchable; denial cannot be mistaken for an
  invalid state. The order and stock are checked unchanged afterward. Order editing
  has no supported API; the direct PUT is deliberately denied by the security boundary.
- Administrator whole-order dispatch: 70/0/70. Filtered shipped-order report and real
  CSV download, dashboard rendering and exact KPI increases relative to initial values.

Chromium and Playwright WebKit run sequentially with one worker, no retries and no
fixed browser sleeps. WebKit is useful Safari-engine coverage on Linux, **not a test
of the Safari application on macOS/iOS**. Firefox, mobile, exhaustive edge cases,
server timeout expiration, multi-warehouse browser scenarios and browser-level
concurrency/load are not covered. Existing JUnit/PostgreSQL tests retain concurrency,
role and transactional coverage. A lifecycle failure stops its dependent steps; CI
reports two browser cases, not one independent test per workflow operation.

## Run against disposable local containers (optional)

Requires Docker/Compose, Node 22.12+ and Python 3. This does not require any cloud
account, production credentials or existing developer database. Run from repository root:

```sh
npm ci --prefix e2e
npm run typecheck --prefix e2e
(cd e2e && node --experimental-strip-types diagnostics.test.mjs && npm audit)
(cd e2e && npx playwright install --with-deps chromium webkit)
docker compose --env-file .env.example -f compose.full.yml build
python3 scripts/e2e-stack.py
```

Port 18081 must be free. The runner deliberately accepts no remote base URL or account
arguments. Direct `playwright test` without the isolated runner refuses to start.
It generates passwords in memory, uses the existing admin bootstrap once, removes
bootstrap credentials before normal startup, and binds nginx to loopback only.
PostgreSQL is internal, with a random Compose project/volume per invocation. Business
identifiers are unique per browser. The runner removes its own containers/volume in
`finally`; if forcibly terminated, inspect and remove only its `operationsflow-e2e-*`
project. Never reuse these credentials or test data elsewhere. No fixture passwords,
storage-state files or credentials are checked in. RabbitMQ is neither started nor
required; application security and CSRF remain enabled and unchanged.

## Diagnostics and security

On failure, CI retains only `e2e/safe-artifacts/` for three days:

- Screenshots mask all inputs, textareas, selects, account identity and authentication
  forms. Viewer failures capture the viewer page as well as the administrator page.
- `action-trace.json` is a deliberately reduced Playwright step timeline: category,
  source line, duration and failure flag. Match source lines to the tested commit.
  No request headers/bodies, cookies, DOM snapshots, locators, error strings, sources
  or account values are serialized. A sentinel test verifies the reporter omits
  sensitive strings from logs and the timeline.

**Native Playwright Trace Viewer ZIPs, videos and HTML reports are disabled** because
raw authenticated traces can expose session cookies, CSRF values and password input.
The reduced timeline is not compatible with Trace Viewer and cannot replay DOM/network
state. This is a deliberate diagnostic limitation, not a claim that native traces are
safe. Raw Playwright error context is never uploaded; the runner removes its private
output directory after execution. Docker output and runtime credentials are not dumped.
Only step source lines, outcomes and durations reach test logs. If a job is forcibly
cancelled, the ephemeral CI runner removes remaining local state on disposal.

## CI, resources and release policy

`.github/workflows/e2e.yml` runs on push and PR using read-only repository permissions,
locked `npm ci`, a dependency audit, two real browser engines and freshly built local
images. No publish/deploy action, provider hook, cloud secret or external service is
used. One Ubuntu runner with normal hosted-runner memory/CPU is sufficient; the job
has a 25-minute cap and one browser worker. It is **not** a 512 MiB / 0.1 CPU test:
existing constrained demo-image checks remain separate. Browser installation and image
builds add several minutes and consume the private repository's existing Actions quota.
If quota is exhausted, stop; there is no paid fallback or guarantee of unlimited free CI.

Release readiness now requires **both** the original five-job `ci.yml` and the single
`browser-e2e` job in `e2e.yml` for the exact current-main push SHA. Missing, skipped,
pending, failed, ambiguous, foreign or stale evidence refuses readiness. The verifier
recollects both workflows to detect changes during collection. PR checks do not replace
post-merge main checks. Messaging and policy-test workflows remain separate review
requirements; they have not been removed or bypassed. Readiness remains point-in-time,
read-only evidence and never authorizes or performs deployment. See
[owner-controlled releases](controlled-deployments.md).

## Verification evidence

Record the exact final feature-branch CI links and results in the pull request.
Local Codex dependency networking and Docker socket access may be unavailable; offline
policy/diagnostic tests are not a substitute for the actual hosted browser run.
