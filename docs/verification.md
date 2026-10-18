# Verification overview

Reviewed on **2026-10-18**. Results below are tied to a specific commit and run;
they are not a promise that every future branch or environment passes.

## Confirmed GitHub Actions evidence

- Commit: [`4a572c4115fd46fa2a0a2cc34e1313d26dca6a6c`](https://github.com/CerenGzn/operationsflow/commit/4a572c4115fd46fa2a0a2cc34e1313d26dca6a6c), merged Milestone 7 on `main`.
- Run: [38976408820](https://github.com/CerenGzn/operationsflow/actions/runs/38976408820), push to `main`, completed successfully on 2026-10-16 UTC.
- Evidence inspected: run metadata, all three job outcomes/steps, backend and frontend job logs.
- The run was named **Foundation CI**. This documentation PR renames only the
  displayed workflow to **OperationsFlow CI**, keeping `.github/workflows/ci.yml`,
  triggers, permissions, jobs, timeouts, commands, and security audits unchanged.

| Check | Confirmed result in that run |
| --- | --- |
| Maven verify / packaging | BUILD SUCCESS |
| JUnit/Surefire | 25 tests; zero failures, errors, or skips |
| PostgreSQL/Testcontainers/Failsafe | 85 tests; zero failures, errors, or skips |
| Frontend dependency installation | `npm ci` succeeded |
| TypeScript | `npm run typecheck` succeeded |
| Vitest | 64 tests in 10 files passed |
| Frontend production assets | `npm run build` succeeded |
| npm audit, all dependencies | Zero reported vulnerabilities |
| npm audit, production dependencies | Zero reported vulnerabilities |
| Docker Compose configuration | Validation job succeeded; this alone does not prove application startup |

[Current workflow runs](https://github.com/CerenGzn/operationsflow/actions/workflows/ci.yml)
are the source for newer results. The README badge tracks `main`; inspect a PR's
own checks before treating its changes as verified. Dependency audits reflect
advisories available at the time, not an enduring security guarantee.

## Verification methods

Source inspection confirms tests for catalog constraints, stock oversell and
release races, idempotency, rollback, deterministic order allocation, cancellation,
whole-order dispatch, authentication/CSRF/roles, actor attribution, analytics,
date boundaries, pagination, and CSV safety. Integration tests use PostgreSQL
containers and explicit test identities with production security enabled.

Frontend tests exercise screen states and request contracts using jsdom and
mocked API responses. They are not real Safari or full browser-to-database tests.
No coverage percentage or independent security certification is recorded.

## Reproduce the checks

From the repository root, with Java 21, curl, Node 22.12+, npm, and a working
Docker daemon available:

```sh
(cd backend && ./mvnw --batch-mode --no-transfer-progress verify)
(cd frontend && npm ci)
(cd frontend && npm run typecheck)
(cd frontend && npm test)
(cd frontend && npm run build)
(cd frontend && npm audit --json)
(cd frontend && npm audit --omit=dev --json)
docker compose --env-file .env.example config --quiet
sh -n backend/mvnw
git diff --check
```

Maven `test` is a useful unit-only check. Maven `package` does not run the Failsafe
integration phase and must not be presented as equivalent to `verify`. Integration
tests must not be skipped to conceal unavailable Docker. Use the committed npm
lockfile with `npm ci`; the application database `.env` is not needed for the
Testcontainers-managed database configuration.

## Local documentation checks (2026-10-18)

Passed: 20 relative documentation links/anchors; JSON/XML manifest parsing;
Maven Wrapper shell syntax; `git diff --check`; and
`docker compose --env-file .env.example config --quiet`. Compose validation
did not start containers or test database connectivity.

The historical verification copy matches the original bytes. The workflow diff
changes only its display name. Expected environment/key/build-output ignore rules
and the `.env.example` exception were checked. Tracked filename screening found
only the intentional `.env.example` template among sensitive-filename candidates;
ignored local artifacts were counted without reading their contents. This is not
a content-based or full-history secret scan.

## Documentation-review scope and limitations

This cleanup changes documentation and the CI display name only. Backend/frontend
tests were **not executed locally for this review**; the passing results above
were verified from GitHub Actions logs. Historical Maven/npm proxy failures and
Docker socket restrictions must not be generalized to every current environment.
No dependency download or socket bypass was attempted during this documentation task.

The following are **not established** by the checked CI run:

- Full browser E2E behavior or Safari/macOS compatibility.
- A deployed application, public demo URL, cloud resource sizing, or hosted TLS/proxy behavior.
- Production load capacity, availability, durable sessions, or backup restoration.
- Absence of every secret or personal record in Git history. Filename/ignore-rule
  checks are a limited hygiene review, not a comprehensive secret scan.

## Historical evidence

The original [Milestone 1 execution record](history/milestone-1-verification.md)
is preserved byte-for-byte. It describes the **2026-10-11** environment, including
blocked Maven/npm downloads, unavailable Docker, missing lockfile, and then-unrun
CI. Those statements are historical and are superseded for the checked commit by
the evidence above. Detailed milestone guides also retain their original scope
and execution context rather than rewriting historical results.

## Milestone 8 containerization

See the [Docker verification record](full-stack-docker.md#verification) for the
current local command outcomes and new CI container smoke job. The main-branch
run above predates containerization and does not validate the new images.

## Milestone 9 preparation checks (2026-10-18)

Maven `verify` was attempted once and failed downloading the wrapper: curl could
not connect to the configured proxy (exit 7). `npm ci --fetch-retries=0
--fetch-timeout=30000` failed with proxy connect EPERM (exit 1); dependent frontend
checks/audits were not run. `docker info` failed with socket operation not permitted,
so image builds and startup could not run locally. No retries or bypasses were used.

The new CI `demo-image` job builds actual bundled assets and runs a disposable
512 MiB/0.1 CPU HTTP smoke test, after existing backend/frontend/Compose jobs.
Java additions test the UI/API boundary, CSRF/readiness/cookies and demo configuration.
No new tests are claimed passing until their CI result is inspected. This does not
validate Render's edge, remote Neon TLS, Safari, real cloud startup or sustained load.
See [the demo guide](free-demo-deployment.md#validation-and-troubleshooting).

Local static checks passed: YAML parsing, both existing Compose configurations,
Python script syntax, relative documentation links/anchors and `git diff --check`.
The original local Dockerfiles, Compose files, dependency manifests and lockfile
are unchanged. These checks do not establish Java compilation or runtime success.

## Startup performance follow-up

[Comparison runs and measurements](startup-performance.md) record a remeasured
baseline, C1 candidate, phase offsets, startup memory, throttling and request-latency
trade-offs. Both comparison workflows passed all existing checks. The normal CI
smoke now verifies the real optimized image entrypoint and retains sanitized metrics;
the optional baseline remains reproducible without changing business/security settings.
