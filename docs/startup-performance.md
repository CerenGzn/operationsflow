# Constrained startup investigation — 2026-10-18

Scope: PR #10, `feature/free-demo-preparation`. No deployment, provider accounts,
secrets or cloud resources were created. The change affects `Dockerfile.demo` only;
the regular backend image and application behavior remain unchanged.

## Evidence and method

The original [run 39145491358](https://github.com/CerenGzn/operationsflow/actions/runs/39145491358)
reported 233.9s first readiness and 216.3s restart. Its logs retained no application
phase breakdown or memory figures, so those cannot be reconstructed retrospectively.

The instrumented comparison at commit `99b2e3a77715c4a997595e5be603c3abb5a9ff35` ran in:

- [Push run 39152953596](https://github.com/CerenGzn/operationsflow/actions/runs/39152953596)
- [PR run 39152957574](https://github.com/CerenGzn/operationsflow/actions/runs/39152957574)

All jobs passed in both comparison runs. Each tested default tiered compilation
(level 4) and C1-only (level 1), with identical Java 21 images, JVM memory caps,
512 MiB container memory/no additional swap and 0.1 CPU quota. Each variant started
against fresh PostgreSQL, completed the full authenticated smoke, then restarted
against the same database without bootstrap credentials and verified session loss.
The database was healthy before application timing began and was not CPU-limited.

Application readiness polling retained the original five-second interval. Times
include container launch, all migrations/schema checks, bootstrap on first start,
and the successful full DB readiness request. Polling and scheduling introduce
several seconds of uncertainty. Separate GitHub-hosted runners introduce hardware
and scheduler variation: two samples per case are evidence, not statistical proof.
These are local PostgreSQL tests, not Neon TLS or Render infrastructure measurements.

The script reads real application logs in memory but exports only fixed phase names
and numeric offsets/durations. Unified JVM startup logging measures VM creation;
`cpu.stat` records container CPU/throttling; `memory.peak` records the startup cgroup
high-water mark, and `/proc/1/status` records Java RSS/high-water RSS. No raw application
logs, connection strings, identities, cookies or passwords are exported. Sanitized
JSON artifacts are retained for seven days; the tables here preserve the findings.

## Before/after measurements

| Run | JVM | Initial readiness | Restart readiness | Initial/restart cgroup peak | Initial/restart Java peak RSS |
| --- | --- | --- | --- | --- | --- |
| 39152953596 | Default tiered | 225.9s | 214.0s | 259.0 / 251.6 MiB | 276.5 / 263.6 MiB |
| 39152953596 | C1 only | 113.6s | 112.7s | 227.2 / 229.2 MiB | 237.2 / 238.9 MiB |
| 39152957574 | Default tiered | 188.1s | 173.8s | 276.0 / 257.9 MiB | 285.6 / 272.8 MiB |
| 39152957574 | C1 only | 121.6s | 112.6s | 227.1 / 226.8 MiB | 237.2 / 236.7 MiB |

Mean initial readiness improved **207.0 → 117.6 seconds (43%)**; mean restart improved
**193.9 → 112.6 seconds (42%)**. Use these contemporaneous comparisons rather than
claiming a universal 50% gain from the older single run. Cgroup and RSS have different
shared-page/accounting rules and are not additive. These are startup peaks, not a
whole-workload memory benchmark or proof that future exports cannot exhaust memory.

### Timing breakdown and bottleneck

Representative initial-start offsets from container start, run 39152953596:

| Log milestone | Default tiered | C1 only |
| --- | --- | --- |
| Spring application main log | 26.9s | 14.0s |
| Repositories scanned | 65.3s | 33.7s |
| Web context initialized | 84.5s | 42.1s |
| Hikari starting → ready | 98.3 → 103.5s | 48.6 → 51.2s |
| Flyway validated → migrations applied | 105.7 → 112.7s | 52.3 → 54.7s |
| Hibernate begins → EntityManagerFactory ready | 114.9 → 151.3s | 55.9 → 76.3s |
| Spring reports started | 208.8s | 106.7s |
| Full readiness succeeds | 225.9s | 113.6s |

VM creation itself took only **0.15–0.23s** with default tiered compilation and
**0.13–0.14s** with C1. Pre-main launcher/class loading, Spring scanning/binding,
Hibernate and subsequent bean/security initialization dominate elapsed time, not
VM creation. The table gives coarse log boundaries, not exclusive component CPU
profiles: work can overlap and includes class loading/compilation. Do not interpret
the Hikari interval as pure network latency or the post-JPA interval as only security.

Default startup used **17.2–22.0 CPU seconds** across the four starts; C1 used
**10.9–11.6 CPU seconds**. Roughly **99–100% of CPU quota periods were throttled**
(98.6% at the lowest C1 sample). At a 0.1 CPU quota, even 11 CPU seconds becomes
about 110 wall seconds. The tier-limit-only experiment strongly implicates compilation
and framework work competing for the tiny CPU budget. It is not a flame graph proving
that every saved second was inside a compiler thread. Aggregate throttled microseconds
can exceed wall time with multiple runnable threads and must not be read as wall delay.

Flyway applied the new schema in a small portion of the timeline. On restart its
already-current schema interval after validation was under one second. Local pool
startup was about 4–6s baseline versus 2.6–2.7s C1. There is no evidence to justify
removing migrations, schema validation or database readiness. Remote Neon connectivity
and wake-up can add further time; this benchmark does not measure them.

## Retained optimization and trade-offs

`Dockerfile.demo` adds **only `-XX:TieredStopAtLevel=1`** to the runtime JVM command.
This avoids the higher JIT tiers for the deliberately short-lived, CPU-starved demo.
The heap/metaspace/code-cache limits, threads, eager initialization, migrations,
Hibernate schema validation, BCrypt cost, authentication, CSRF, audit, transactions,
readiness and tests are unchanged. No lazy-initialization or verification bypass is
used. The general-purpose backend Dockerfile keeps its normal tiered compilation.

C1 can reduce peak throughput for warmed-up, CPU-heavy workloads because C2's deeper
optimization is unavailable. [Spring's startup discussion](https://spring.io/blog/2018/12/12/how-fast-is-spring/)
describes this trade-off; our decision rests on the measurements above, not generic
advice. Do not carry this flag into an unconstrained production deployment by default.
Rollback is to remove the one tier-limit argument from `Dockerfile.demo` and rebuild.

Short post-restart probes did not regress: login took **4.9–6.6s default** versus
**4.7–4.8s C1**. First empty-dashboard requests took **2.0–2.3s** versus **0.7–0.9s**;
four subsequent reads were **0.3–0.6s** versus **0.1–0.4s**. These are five sequential
empty-dataset reads and a login per run, not sustained throughput, populated reporting,
p95 latency or a promise that C1 is always faster. BCrypt strength was not reduced.

## Reproduce and ongoing CI

In a supported cloud workspace with Docker, build once and run the variants sequentially:

```sh
docker build -f Dockerfile.demo -t operationsflow-demo:ci .
STARTUP_JIT=default python3 scripts/bundled-image-smoke.py
STARTUP_JIT=c1 python3 scripts/bundled-image-smoke.py
# Verifies the real image entrypoint, without overriding its JIT configuration:
python3 scripts/bundled-image-smoke.py
```

The optional comparison changes only the tier argument in the inspected image
entrypoint. The normal CI job runs the actual image default and uploads sanitized
`startup-image.json`. The temporary two-variant matrix is removed from every-push
CI to avoid spending another eight minutes re-proving the baseline on each change.
Backend Maven verify, frontend typecheck/tests/build/audits, Compose and existing
full-stack container smoke checks remain mandatory and unchanged.

Local Docker socket access remained blocked (`operation not permitted`). Script/YAML
syntax and diff checks passed locally; actual images and application tests ran in
GitHub Actions. No restricted networking or Docker permissions were bypassed.

## Free-hosting recommendation

**Improved, but not a quick or proven-reliable cold public demo.** Approximately two
minutes of application startup remains under the requested cap, before any additional
Render wake-up/Neon delay. Logins around five seconds are also noticeable. Startup
memory fit the cap in these runs; CPU is the binding constraint. No paid upgrade is
recommended and no provider availability/cost guarantee is inferred.

Render Free remains a conditional option for a scheduled, owner-supervised review:
open it manually ahead of the appointment, verify readiness, and explain possible
session loss after sleep. Do not use uptime bots to defeat free-tier suspension.
Do not make an unattended cold URL the primary recruiter experience. Prefer a concise
walkthrough/video and screenshots, with live access offered by arrangement. Actual
Render HTTPS/Neon TLS, Safari, loaded reporting and repeated hosted cold starts still
require separate approval and validation before exposing the service.
