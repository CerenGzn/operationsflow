# Owner-controlled releases — readiness automation, manual deployment

Milestone 12 adds a **non-deploying** GitHub Actions readiness check. Deployment is
still a separate owner decision in the Render dashboard, not automatic CD. No hook,
provider API key, production secret, cloud resource or deployment is created here.
Render Auto-Deploy settings are neither inspected nor changed by this task.

The owner reports successful live synthetic workflows through whole-order dispatch,
stock deduction, reporting, administrator login and DEMO_VIEWER access, with Flyway
V8 deployed. These are owner-reported manual results, not automated live E2E evidence.
No provider account, deployed SHA or live configuration was independently inspected.

## Options assessed (2026-10-19)

| Option | Approval / exact commit / CI | Secrets and free-plan practicality | Decision |
| --- | --- | --- | --- |
| A. Render dashboard after readiness check | Owner explicitly selects a full SHA and confirms deployment; readiness independently verifies current main CI | Existing browser account; no production secrets in GitHub, no extra infrastructure; uses existing free quotas | Recommended |
| B. Actions workflow_dispatch + deploy hook | Manual dispatch alone is not an independent owner approval gate. A safe implementation also needs trusted code, exact SHA, CI checks, serialized deploys and final status verification | Hook is a bearer credential; its URL must never be an input, log or tracked value. Render supports a `ref` SHA, but hook storage/creation violates this task's restrictions; GitHub environment approval availability is not assumed | Not implemented |
| C. Native Render “After CI Checks Pass” | Automatically deploys changes and is therefore incompatible with explicit approval per release; Render also treats some neutral/skipped checks as passed | No new hook, but changes provider settings and may release on merge | Rejected |
| C. Manual Render CLI/API | Can select a SHA, but still needs separately verified CI and explicit approval | Provider token and additional tooling; no safety advantage over the browser, no approved credential channel | Not implemented |

The selected path needs no paid environment approvals, additional GitHub permissions
for the connector, or new services. Private-repository Actions minutes and Render
build/runtime quotas still apply; check current allowances privately and stop if
exhausted. No automatic purchase, paid upgrade or guarantee of perpetual zero cost.

## What the automated check proves

`.github/workflows/release-readiness.yml` runs only on `workflow_dispatch`.
Choose **main** in the workflow branch selector. It rejects a feature-branch or PR
execution context before checkout. The checked-out verifier is the immutable main
snapshot for that run, not code selected by the input. Credentials are not persisted
in Git configuration. The only token is GitHub's automatically issued, short-lived
`GITHUB_TOKEN`, with `contents: read` and `actions: read`; no repository secret is
created, retrieved or modified. This is not a production credential.

Input `commit_sha` must be a full lowercase 40-character SHA. Deliberately stricter
than “some ancestor of main,” the candidate must equal **both the workflow run's
main snapshot and the current main tip**. Historical commits and pre-merge PR SHAs
are refused. If main advances, rerun against its new green tip. Historical rollback
is a separate reviewed recovery process, not a bypass input to this verifier.

The verifier makes GET requests only to the GitHub API for this repository:

1. Verify main's exact ref and the active `.github/workflows/ci.yml` workflow identity.
2. Find exactly one main-branch **push** run of that workflow for the SHA. No PR run,
   different repository/workflow, missing run or multiple matches is accepted.
3. Require `completed` / `success` on the run's current attempt.
4. Read the explicit attempt's paginated jobs and require exactly one successful
   `backend`, `frontend`, `compose`, `containers` and `demo-image`. Check SHA, run ID
   and attempt provenance for every job. Pending, skipped, neutral, failed, duplicate,
   missing and unexpected jobs all fail closed. Existing job names are an explicit
   policy contract; future CI job changes require reviewing this allowlist.
5. Recheck run selection, current attempt and main before reporting success. API
   failures, redirects, incomplete/inconsistent pagination or schema changes fail
   closed. Responses/errors do not echo token values or arbitrary API bodies.

The Actions summary contains the exact SHA, CI run/attempt link, required jobs and
UTC verification timestamp. It explicitly says **NO DEPLOYMENT**. Failures report
NOT READY and exit nonzero. It creates no release, deployment, tag, comment, artifact,
hook or cloud request. The separate `release-readiness-tests.yml` runs offline policy
fixtures on push/PR; it does not perform a readiness decision or deployment.

`.github/workflows/ci.yml`, npm ci, audits, container checks, application logic,
authentication, database migrations and `Dockerfile.demo` remain unchanged. The
readiness decision targets the existing five-job CI workflow, not every unrelated
workflow in the repository; the policy-test workflow must also be green when reviewing
changes to the verifier itself.

## Browser-only release procedure

**Before merging:** the owner must confirm the existing Render service's Auto-Deploy
is Off. Otherwise merging could deploy independently of this workflow. The historical
`deploy/render-free.yaml` setting is only a reference, not proof of the live setting.
If it is not Off, pause and handle that cloud change separately; this task changes none.

1. Approve and merge the reviewed PR. Copy the full resulting **main commit SHA**.
2. Wait for OperationsFlow CI on that main push to pass all five jobs. Do not use
   only the PR check or a green badge for an older commit. For verifier changes,
   also require Release readiness tests to pass.
3. GitHub → repository → **Actions → Release readiness (no deployment) → Run workflow**.
   Select **main**, paste the full SHA into `commit_sha`, and run it. This workflow
   appears after its definition is merged into the default branch. Read the summary
   and open its exact CI run/attempt link. If NOT READY, resolve the stated reason
   and rerun; never deploy by ignoring a failed result.
4. Reconfirm Render Auto-Deploy is **Off**, main has not moved, and the cited CI attempt
   is still successful. Check current free quotas and schema/rollback compatibility.
   Record the currently running SHA and retain an approved recovery target.
5. Explicitly approve this exact SHA for release. In Render's existing service, choose
   **Manual Deploy → Deploy a specific commit** and paste that full SHA. Check it
   against the readiness summary. Do not choose “Deploy latest commit.”
6. Explicitly confirm **Deploy Commit**. This is the deployment action; running the
   GitHub verifier alone never performs it. Confirm Auto-Deploy remains Off afterward.
7. Watch Render's deploy result privately. Allow several minutes for free-tier cold
   start. Check [demo health](https://operationsflow.onrender.com/actuator/health/demo),
   then [full readiness](https://operationsflow.onrender.com/actuator/health/readiness).
   Demo health deliberately avoids a database query; full readiness includes Neon
   and can wake its compute. One UP response is not comprehensive functional proof.
   Check HTTPS administrator login/logout, DEMO_VIEWER read-only screens, reporting
   and a small owner-approved synthetic operational workflow relevant to the release.
   Do not repeatedly submit uncertain stock-changing requests: inspect their result
   first. Do not publish cookie values, connection strings or credentials.
8. Record the deployed SHA, readiness/CI links, deployment outcome and manual checks
   in a private release note or repository discussion/issue. Record failures honestly.
   A docs/automation-only merge does not itself require an application deployment.

Suggested non-secret record:

```text
Approved main SHA:
Readiness run URL and verification time:
CI run URL / attempt:
Previously running SHA:
Owner's deployment decision and time:
Observed deployed SHA / outcome:
Demo health / database readiness:
Admin and viewer smoke outcomes:
Synthetic workflow checked / discrepancies:
Rollback compatibility reviewed:
```

## Failure and database-safe recovery

Render/Neon sleep, quotas and service incidents can delay readiness. Wait a bounded
period and inspect provider status/logs privately; do not weaken migrations, security
or health checks, run artificial keep-alive polling, or buy resources automatically.
Do not blindly restart while stock-changing operations may have completed. Once the
service recovers, reauthenticate and inspect recorded order/balance state.

A failed deploy may leave the last successful application serving, but **database
migrations may already have committed**. Confirm the actual running SHA and schema
before proceeding. The owner can use an available retained Render rollback or a
separately approved known-good commit only if compatible with the current database.
Free deploy-history/recovery retention is limited; verify availability rather than
assuming an unlimited rollback history. Rebuilding the same SHA is not byte-identical
artifact promotion: base tags and dependency retrieval can change.

Flyway runs during startup in the existing image. Prefer additive, forward-compatible
migrations and reviewed forward fixes. Never delete/repair migration history to force
old code to start, or automatically restore/drop the Neon database. A restore can lose
business writes, accounts and audit records. Before any future schema-changing release,
the owner must verify an available private backup/recovery procedure and approve its
use separately. Free recovery retention is not a tested backup guarantee.

V8 is already deployed according to the owner. Older binaries cannot deserialize
DEMO_VIEWER, even for inactive users. See [viewer rollback limits](demo-viewer.md#database-and-rollback):
all viewer rows require controlled deactivation/reassignment while kept inactive before
an older-role binary could be considered, plus Flyway validation compatibility review.
No such changes are made here. Code rollback does not reverse schema or business data.

After recovery, repeat readiness/login and targeted synthetic checks, record results,
and stop further releases until failures are understood.

## Guarantees and limits

- No Render/Neon access, production secrets, deployment capability or write token exists
  in the readiness workflow. It cannot establish actual provider settings or live SHA.
- This is a point-in-time report, not a lock, signed release attestation or owner approval.
  Main/CI can change afterward; recheck immediately before manually deploying. The owner
  must choose the matching SHA. GitHub permissions determine who can manually run the
  check; this workflow does not assume that only one repository collaborator can run it.
- Trust comes from reviewed code on main and GitHub Actions. A collaborator able to
  modify main/workflows can change the policy. Branch protection and environment approval
  availability were not inspected or assumed; no new permissions are requested.
- API denial/rate limits or expired run evidence mean NOT READY, not a fallback to a
  badge, unchecked SHA, PR workflow, secret-bearing input or automatic deployment.
- Tests exercise success and refusal paths using offline fixtures. A trusted main
  workflow_dispatch cannot be exercised before this PR is merged. The owner must run
  the first real readiness check after merge and inspect its summary before any release.

Provider references reviewed 2026-10-19: [deploy controls](https://render.com/docs/deploys),
[deploy hooks](https://render.com/docs/deploy-hooks), [rollbacks](https://render.com/docs/rollbacks).
Use current provider behavior and quotas at release time; this is not an enabled CD pipeline.
