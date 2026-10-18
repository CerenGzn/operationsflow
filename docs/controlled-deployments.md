# Controlled deployment proposal — not enabled

This Milestone 10 change is documentation only. It creates no hooks, GitHub secrets,
resources, credentials or deployments, and does not change the live application.
The owner reports Render Free + Neon Free hosting. Account settings and the deployed
revision have not been independently inspected.

## Current repository state

`.github/workflows/ci.yml` runs on pushes and pull requests with read-only repository
permissions. It checks backend tests/build, frontend npm ci/typecheck/tests/build,
all and production npm audits, Compose, full-stack container smoke, and the bundled
image under 512 MiB / 0.1 CPU. It neither publishes images nor deploys them.

`deploy/render-free.yaml` is a historical reference with `autoDeployTrigger: 'off'`,
`Dockerfile.demo`, the demo profile, HTTPS-edge acknowledgement and the lightweight
health path. It does not establish the actual Render service's settings. Do not
apply it to the existing service: creation/application can provision or redeploy.

## Recommended option: owner-approved dashboard deployment

Keep CI and deployment separate. An authorized owner performs this future procedure
in the browser only after a separate deployment decision; no GitHub deployment
secret, new infrastructure or local tooling is required.

1. Before merging any change, privately confirm that the live Render service's
   Auto-Deploy setting is **Off**. The repository reference alone cannot ensure this.
   Neither On Commit nor After CI Checks Pass meets the requirement for approval
   before each deployment. No setting is changed by this task.
2. Review and merge an approved PR manually. Record the exact resulting `main` SHA.
   Require successful backend, frontend, compose, containers and demo-image jobs
   for that SHA; pending, skipped or neutral checks do not count as success. Do not
   substitute a pre-merge PR run for the resulting main commit's run.
3. Review runtime/configuration changes, Flyway compatibility, current free quotas
   and recovery options. Record the currently deployed SHA and a suitable rollback
   target. A docs-only merge does not require deployment.
4. Obtain explicit owner approval for that specific SHA. In Render, choose
   **Manual Deploy → Deploy a specific commit**, verify the SHA and deploy only
   after approval. Do not use “latest commit,” which can move after CI review.
   Confirm Auto-Deploy remains Off. Use only the existing free service/database.
5. Watch the deployment privately. Allow for cold start; check demo health, then
   full database readiness, HTTPS login, password/session behavior and logout.
   Confirm Secure/HttpOnly/SameSite cookie attributes and no mixed-content errors
   in browser tools without sharing cookie values. Confirm unauthenticated API
   access is rejected and an operator cannot enter user administration.
6. With owner-approved synthetic records, repeat receipt → order reservation →
   cancellation/release. Verify unchanged totals afterward. For relevant releases,
   also check fulfillment, reports and role boundaries. Record SHA, CI run, deploy
   result and manual outcomes without credentials or personal information.

Rebuilding a commit on Render is not identical artifact promotion: base image tags
and permitted dependency downloads can change. Lockfiles help reproducibility but
do not make the image supply chain immutable. Digest pinning and approved artifact
promotion are future work, not a reason to publish an image during this task.

## Other options and why they are deferred

- **Native “After CI Checks Pass”:** supports CI gating, but automatically deploys
  changes and lacks the requested per-deployment owner decision. Do not enable it.
- **Manual GitHub Actions deployment via Render hook:** possible as a separate
  proposal, not implemented. A hook URL is a bearer credential; never put one in
  source, documentation, workflow inputs, logs or artifacts. Render supports a
  `ref` query parameter for an exact commit SHA. Any future workflow would need
  explicit manual dispatch, trusted main-branch workflow code, validation of the
  approved SHA and its successful CI, serialized deployments, and a real approval
  gate. Do not run deployment code from untrusted pull requests. A hook response
  only starts/queues a deploy; it does not prove health or successful completion.
  Protected environment approval availability depends on GitHub plan/repository
  visibility and must not be assumed for this private repository.
- The current rule prohibits production secrets in GitHub, including a deploy hook.
  Consequently a GitHub hook workflow is **not compatible with the current scope**.
  It would require a separately approved credential-management policy and supported
  approval mechanism; no secret should be requested or created now. The dashboard
  method avoids this conflict. Rotate a compromised hook privately at the provider.

## Rollback and recovery

Stop further releases if verification fails. Compare the deployed SHA with the
approved one and inspect sanitized errors. The owner can use Render's available
rollback action for a retained successful deploy, or manually deploy a known-good
commit if rebuilding is appropriate. Check retained-deploy availability in the
account; do not assume unlimited history on Free. A rebuild may produce different
base image contents. Recheck configuration separately instead of assuming code
rollback restores every setting.

**Code rollback does not reverse Flyway migrations or business writes.** Only use
an older application if compatible with the current schema. Prefer forward-compatible
migrations and a forward fix when downgrade compatibility is uncertain. Never repair,
disable or delete Flyway history to force an old image to start. Do not automatically
restore/drop the Neon database; that could lose intervening writes and user changes.
Before future schema-changing releases, the owner must confirm an available private
recovery point or safe backup procedure and explicitly approve any restore. Free
recovery windows are limited and are not a verified backup strategy.

After any recovery, repeat readiness, login and targeted synthetic workflow checks.
Session loss requires reauthentication. Record the result and keep deployment
approval closed until unresolved failures are understood. Provider outages or quota
suspension can require waiting rather than changing code or purchasing resources.

## Remaining owner actions

Review this draft and the CI evidence. Before a future merge/deployment, confirm
Auto-Deploy is Off and retain the currently running SHA. Arrange individual reviewer
accounts and deactivate them afterward. Privately verify live settings, quotas and
recovery options; none were inspected here. Only after a separate deployment approval
should the owner follow the exact-commit procedure above. No MacBook access, new
repository permissions or production credentials need to be shared with this agent.

Provider references reviewed 2026-10-18: [deploy controls](https://render.com/docs/deploys),
[secret deploy hooks](https://render.com/docs/deploy-hooks),
[rollback behavior](https://render.com/docs/rollbacks). Recheck provider behavior before
performing a future release; this document is a proposal, not an enabled pipeline.
