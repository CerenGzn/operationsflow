# Full-stack Docker (Milestone 8)

This is a local, synthetic-data stack, not a cloud deployment. Only Docker Engine
with Compose v2 (supporting `up --wait`) is required on the host; Java, Maven and
Node run in build containers. Initial builds need registry, Maven Central and npm
access. Commands below run from the repository root in bash. No personal-file
permissions or cloud account is required.

## Prepare and build

```sh
# Only if .env does not already exist:
cp -n .env.example .env
# Privately edit .env: replace POSTGRES_PASSWORD with a unique local password.
# Keep DB_PASSWORD equal for the separate host-development workflow.
docker compose -f compose.full.yml build
docker compose -f compose.full.yml up -d --wait postgres
```

Use a literal single-quoted value in `.env` if a password contains `$` or `#`.
Never commit `.env` or run a non-quiet `compose config` in shared logs. Full-stack
Compose derives the internal JDBC URL and database credentials from `POSTGRES_*`;
it deliberately ignores the host-development `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`.
`APP_PORT` optionally changes the localhost port (default 8080).

## First administrator: empty database only

There are no default credentials. Do this once, before starting the normal stack.
Use a synthetic email and a unique password of 12–72 UTF-8 bytes. In bash, without
shell tracing (`set -x` must be off):

```sh
read -r -p 'Synthetic administrator email: ' BOOTSTRAP_ADMIN_EMAIL
read -r -s -p 'New administrator password: ' BOOTSTRAP_ADMIN_PASSWORD
printf '\n'
export BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD
docker compose -f compose.full.yml run --rm --name operationsflow-bootstrap \
  --no-deps -e BOOTSTRAP_ADMIN_EMAIL -e BOOTSTRAP_ADMIN_PASSWORD backend
```

This foreground container runs the existing bootstrap and creates the administrator
and audit event transactionally. In a second terminal, check:

```sh
docker inspect --format '{{.State.Health.Status}}' operationsflow-bootstrap
```

Wait for `healthy`. Then press Ctrl-C in the first terminal, wait for the temporary
container to be removed, and run in that same terminal:

```sh
unset BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD
docker compose -f compose.full.yml up -d --build --wait --wait-timeout 180
```

Open **http://localhost:8080** and sign in. Bootstrap variables are never passed to
the normal backend service. If bootstrap fails, remove the variables and inspect
the failure before retrying; an existing user table intentionally rejects bootstrap.
Do not erase data to recover an administrator. Docker administrators can inspect
container environments while the temporary container exists; use a trusted host.

## Subsequent starts, health and stop

```sh
docker compose -f compose.full.yml up -d --build --wait --wait-timeout 180
docker compose -f compose.full.yml ps
docker compose -f compose.full.yml down
```

The browser can check `/actuator/health/readiness` through the frontend; readiness
includes PostgreSQL. `/healthz` checks nginx alone. Backend and frontend images
contain their health checks. Compose waits for database health before backend,
and backend readiness before frontend. An unhealthy running container is not
restarted automatically by Docker's restart policy; inspect and repair the cause.

The full stack uses its own `operationsflow-full` project and database volume;
the existing `compose.yaml` developer volume is untouched. `down` retains data.
**Only for intentional deletion of all full-stack data**, use
`docker compose -f compose.full.yml down -v`. Changing `.env` does not rotate an
existing database password. Backups/restoration need a separate reviewed procedure.

## Routing and security

Only nginx port 8080 is published, bound to 127.0.0.1. Backend and PostgreSQL have
no host-published ports. Separate internal application/database networks restrict
connectivity; the frontend has an edge network for the localhost entry point.
React assets and APIs share one origin; nginx proxies `/api`, `/actuator`, and
ADMIN-protected Swagger/OpenAPI paths without rewriting cookies or paths. Unknown
SPA routes return `index.html`; missing assets return 404. Docker DNS is re-resolved
so a replaced backend does not leave nginx pointing at an old address.

Backend and frontend runtimes are non-root with dropped capabilities and
no-new-privileges. The backend runtime includes a JRE, not Maven, Node, or a JDK.
Allowlisted build contexts exclude `.env`, local build artifacts, and credentials.
No Vite environment file or build-time secret is copied into assets. Dependency
versions and npm lockfile are preserved. Base image tags are versioned but mutable;
this is not a byte-for-byte reproducible or independently scanned image supply chain.
Pin reviewed multi-platform digests and scan images before any hosted release.

Authentication, authorization, CSRF, session timeout and audit rules are unchanged.
This stack is **HTTP on localhost** with `SESSION_COOKIE_SECURE=false`; HttpOnly
and SameSite=Strict remain enabled. Do not publish it on the internet. Future HTTPS
hosting requires Secure cookies, trusted proxy handling and TLS review. Forwarding
headers are stripped; all proxied users share nginx's source IP for the existing
IP login limiter (account limits still apply). Sessions and limiter state remain
process-local and reset on backend restart. No sticky/multi-replica guarantee.
Static security headers are provided by nginx; request access logging is disabled.
Use synthetic business records only.

## Troubleshooting

- Port in use: set `APP_PORT=8081` in `.env`, then recreate the frontend and use that port.
- Backend unhealthy: inspect `docker compose -f compose.full.yml logs backend` privately;
  check database credentials, migrations, and whether bootstrap was already completed.
- Login loop: use the same hostname consistently, keep Secure disabled for local HTTP,
  and clear old localhost cookies if switching between host-development and Docker.
- A 502 after backend replacement should clear after DNS refresh (10 seconds).
- Registry/download failures: verify permitted network access; do not disable TLS checks.
- Developer database is absent: the two workflows intentionally have different volumes.

## Verification

CI preserves Maven `verify` (including PostgreSQL Testcontainers), frontend
`npm ci`, typecheck, tests, production build and both security audits. A new
`containers` job depends on those jobs, builds both images without publishing,
and runs `python3 scripts/container-smoke.py`. That script creates random transient
credentials, a dedicated disposable CI project, verifies readiness, SPA routing,
CSRF rejection, login/cookies, protected database reads, logout and proxy recovery,
and deletes only its own CI volume afterward. Do not use that script for a demo
whose data should persist. It is HTTP integration smoke coverage, not browser E2E.

On 2026-10-18 in Codex Cloud, both Compose files passed static configuration
validation and the smoke script passed Python syntax validation. Docker daemon
access failed with socket `operation not permitted`, so image builds/startup and
authenticated runtime checks could not run locally. Maven verify failed while
fetching the wrapper (proxy connection failed); `npm ci` failed with proxy
`connect EPERM`. Frontend typecheck/tests/build/audits could not run after that
failed installation. No retry or permission bypass was attempted. PR CI results
must be consulted for runtime/build verification; earlier milestone results are
not proof these Dockerfiles work.
