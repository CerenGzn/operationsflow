# Frontend dependency security review — 2026-10-14

Scope: Draft PR #4, `feature/milestone-4-orders`. Repository and GitHub Actions access only; no local files were accessed. No deployment or merge.

## Evidence and dependency chains

The [baseline Actions run](https://github.com/CerenGzn/operationsflow/actions/runs/38612150945) contains the full `npm audit --json`, production-only audit and `npm ls --all` output. The initial report counted **four affected packages**, not four distinct advisories: one moderate, one high and two critical.

| Affected package | Installed version | Chain from frontend | Audit severity | Scope |
| --- | --- | --- | --- | --- |
| vite | 7.1.9 | direct dev dependency; also shared by vitest, vite-node and the React plugin | High | Development server/build tool |
| vitest | 3.2.4 | direct dev dependency | Critical, including inherited findings | Test runner |
| @vitest/mocker | 3.2.4 | vitest → @vitest/mocker | Moderate | Test mocking |
| tinypool | 1.1.1 | vitest → tinypool | Critical | Test worker processes |

All affected lockfile entries are development dependencies. The baseline `npm audit --omit=dev --json` reported zero vulnerabilities. React and React DOM remain 19.1.1; no production dependency version changed.

### Advisories and actual exposure

Vite 7.1.9 was covered by six advisories:

- [GHSA-93m4-6634-74q7](https://github.com/advisories/GHSA-93m4-6634-74q7): filesystem deny bypass using backslashes on Windows.
- [GHSA-4w7w-66w2-5vf9](https://github.com/advisories/GHSA-4w7w-66w2-5vf9): path traversal in optimized dependency source-map handling.
- [GHSA-v2wj-q39q-566r](https://github.com/advisories/GHSA-v2wj-q39q-566r): filesystem deny bypass using query strings.
- [GHSA-p9ff-h696-f583](https://github.com/advisories/GHSA-p9ff-h696-f583): arbitrary file read through the development-server WebSocket.
- [GHSA-v6wh-96g9-6wx3](https://github.com/advisories/GHSA-v6wh-96g9-6wx3): launch-editor UNC path handling can disclose NTLMv2 hashes on Windows.
- [GHSA-fx2h-pf6j-xcff](https://github.com/advisories/GHSA-fx2h-pf6j-xcff): filesystem deny bypass through alternate Windows paths.

These affect tooling on the developer's machine, particularly reachable development-server endpoints; they are not vulnerabilities embedded in the static React bundle. Scripts bind Vite to loopback, which reduces exposure but is not a substitute for patching. Windows-specific cases do not apply to the Linux CI runner or macOS, while the other server file-read cases are not Windows-only.

Vitest and its dependencies:

- [GHSA-5xrq-8626-4rwp](https://github.com/advisories/GHSA-5xrq-8626-4rwp): arbitrary file read/execution when the Vitest UI server is listening; affects Vitest <3.2.6. This project uses `vitest run`, with no UI/API server configured or @vitest/ui installed.
- [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9): redirect-mock path traversal/arbitrary file read; reported against both Vitest and @vitest/mocker, versions >=2.1.0 <4.1.11. The project uses jsdom tests, not browser-mode mocking/server configuration.
- [GHSA-5gmw-xhrv-c9v3](https://github.com/advisories/GHSA-5gmw-xhrv-c9v3) and [GHSA-85c8-ppgw-ccpr](https://github.com/advisories/GHSA-85c8-ppgw-ccpr): prototype-pollution gadgets in Tinypool worker/run options can lead to code execution. The combined affected range is <2.1.2. This is conditional on polluted or attacker-controlled options; OperationsFlow supplies no request-derived custom worker options. It remains a developer/CI process risk and was removed, not waived.

## Smallest coherent fix and compatibility assessment

- **Vite 7.1.9 → 7.3.7**: stays on major 7 and selects its current security patch rather than Vite 8. Its Node requirement (^20.19 or >=22.12) is satisfied by this project's Node 22.12+ requirement and Node 22 CI. Existing React plugin 5.0.4 accepts Vite 7.
- **Vitest 3.2.4 → 4.1.11**: a deliberate, tested major upgrade. The [same-major 3.2.7 trial](https://github.com/CerenGzn/operationsflow/actions/runs/38612361935) removed the UI-server advisory but still reported three affected packages: it pins mocker 3.2.7 and depends on Tinypool ^1.1.1. A patch-only upgrade therefore did not fix the known findings.
- 4.1.11 is the first identified fixed version for the redirect-mock advisory. It uses mocker 4.1.11 and no longer depends on Tinypool. Its declared Node 22, Vite 7 and jsdom compatibility matches the project. No custom pools, browser-mode or coverage-plugin configuration needs migration. Existing assertions, mocks, setup hooks and component tests pass unchanged. There is no need to jump to npm's suggested Vitest 5.
- Only these two direct development dependency versions change. Necessary transitive updates include Vitest's 4.1.11 packages, Chai 6 and Vite's compatible esbuild 0.28 packages. npm also updates peer metadata. No overrides, forced resolution, `npm audit fix --force`, or production-library changes are used.

## Reproducible lockfile and verification

npm 10.9.9 crashed internally with “Cannot read properties of null (reading 'edgesOut')” while resolving the cross-major candidate. The lockfile was generated successfully in Actions using the pinned command:

```sh
npx --yes npm@11.6.2 install --save-dev --save-exact --package-lock-only --ignore-scripts vite@7.3.7 vitest@4.1.11
```

Both package files are committed together. The lockfile is npm-generated, including registry URLs and integrity hashes; it was not hand-constructed. The [candidate run](https://github.com/CerenGzn/operationsflow/actions/runs/38612824148) then passed the existing npm 10 `npm ci`, typecheck, all 29 frontend tests and production build. Full and production-only audits both returned zero findings.

Temporary generation/metadata/diagnostic steps are removed from final CI. CI installs the committed lockfile using `npm ci` and now runs blocking full and production-only audit checks. The existing backend Maven verify/PostgreSQL Testcontainers and Compose jobs remain unchanged. Final committed-head verification is linked in PR #4.

No known npm audit findings remain for the reviewed lockfile at this review date. Audit coverage depends on the advisory database and is not proof that dependencies contain no undiscovered vulnerabilities. Local execution and macOS were not used in this review.
