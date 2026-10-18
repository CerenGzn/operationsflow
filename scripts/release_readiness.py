"""Read-only GitHub release evidence. Never contacts a deployment provider.

Only the current main tip is eligible. Uses stdlib, GET requests and the ephemeral
read-only Actions token. Candidate code and returned API URLs are never executed.
"""
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

REPOSITORY = "CerenGzn/operationsflow"
WORKFLOW = ".github/workflows/ci.yml"
REQUIRED_JOBS = frozenset({"backend", "frontend", "compose", "containers", "demo-image"})


class NotReady(Exception):
    """Safe, non-secret explanation for refusing readiness."""


def require(condition, message):
    if not condition:
        raise NotReady(message)


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise NotReady("GitHub API redirect refused; no credentials forwarded.")


class GitHub:
    def __init__(self, token):
        require(bool(token), "Ephemeral GitHub token unavailable.")
        self.token = token
        self.opener = build_opener(NoRedirects())

    def get(self, path):
        require(path.startswith("/") and not path.startswith("//"), "Invalid API path.")
        request = Request("https://api.github.com/repos/" + REPOSITORY + path,
                          headers={"Authorization": "Bearer " + self.token,
                                   "Accept": "application/vnd.github+json",
                                   "X-GitHub-Api-Version": "2022-11-28"}, method="GET")
        try:
            with self.opener.open(request, timeout=20) as response:
                require(response.status == 200, "Unexpected GitHub API response.")
                return json.load(response)
        except HTTPError as error:
            # Do not include response bodies, request objects, headers or tokens.
            raise NotReady(f"GitHub API HTTP {error.code}; check read permissions/availability.") from None
        except (URLError, TimeoutError, ValueError):
            raise NotReady("GitHub API unavailable or returned invalid JSON.") from None


def collection(api, path, field):
    """Bounded pagination; reject inconsistent/truncated collections, never silently pass."""
    values = []
    total = None
    for page in range(1, 11):
        separator = "&" if "?" in path else "?"
        data = api.get(f"{path}{separator}per_page=100&page={page}")
        count = data.get("total_count")
        require(type(count) is int and 0 <= count <= 1000, "Invalid or excessive API collection size.")
        require(total is None or total == count, "API collection changed during pagination; rerun.")
        total = count
        batch = data.get(field)
        require(isinstance(batch, list), "Missing API collection.")
        values.extend(batch)
        require(len(values) <= total, "Inconsistent API collection.")
        if len(values) == total:
            return values
        require(bool(batch), "Truncated API collection.")
    raise NotReady("API pagination limit reached; evidence incomplete.")


def main_tip(api):
    ref = api.get("/git/ref/heads/main")
    require(ref.get("ref") == "refs/heads/main" and ref.get("object", {}).get("type") == "commit",
            "Main reference is not a commit.")
    return ref["object"]["sha"]


def validate_run(run, sha, workflow_id):
    require(run.get("head_sha") == sha and run.get("head_branch") == "main", "CI SHA/branch mismatch.")
    require(run.get("event") == "push", "Only main push CI is accepted; PR/dispatch evidence refused.")
    require(run.get("workflow_id") == workflow_id and run.get("path") == WORKFLOW, "Wrong CI workflow.")
    require(run.get("repository", {}).get("full_name") == REPOSITORY and
            run.get("head_repository", {}).get("full_name") == REPOSITORY, "Foreign repository CI refused.")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "CI must be completed with success (not pending, skipped, neutral or failed).")
    require(type(run.get("run_attempt")) is int and run["run_attempt"] > 0, "Missing CI attempt.")


def verify(api, sha, executing_sha):
    require(bool(re.fullmatch(r"[0-9a-f]{40}", sha)), "Enter a full lowercase 40-character commit SHA.")
    require(sha == executing_sha, "Selected SHA must match this workflow's main snapshot; start a new run.")
    require(main_tip(api) == sha, "Only the current main tip is eligible; stale/PR/foreign SHA refused.")
    workflow = api.get("/actions/workflows/ci.yml")
    require(workflow.get("path") == WORKFLOW and workflow.get("state") == "active" and
            type(workflow.get("id")) is int, "CI workflow is missing, inactive or ambiguous.")
    query = urlencode({"branch": "main", "event": "push", "head_sha": sha})
    path = f"/actions/workflows/{workflow['id']}/runs?{query}"
    runs = collection(api, path, "workflow_runs")
    require(len(runs) == 1, "Expected exactly one main push CI run for this SHA; missing/ambiguous evidence.")
    run = runs[0]
    validate_run(run, sha, workflow["id"])
    run_id = run.get("id")
    require(type(run_id) is int and run_id > 0, "Invalid CI run ID.")
    # Explicit latest attempt prevents an older successful attempt hiding a rerun.
    current = api.get(f"/actions/runs/{run_id}")
    validate_run(current, sha, workflow["id"])
    require(current.get("id") == run_id and current["run_attempt"] == run["run_attempt"], "CI attempt changed; rerun readiness.")
    attempt = current["run_attempt"]
    jobs = collection(api, f"/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
    require(Counter(job.get("name") for job in jobs) == Counter(REQUIRED_JOBS),
            "Required CI jobs missing, duplicated or unexpected; review policy before release.")
    require(len({job.get("id") for job in jobs}) == len(jobs), "Duplicate job IDs.")
    for job in jobs:
        require(type(job.get("id")) is int and job.get("run_id") == run_id and
                job.get("head_sha") == sha and job.get("run_attempt") == attempt,
                "Job provenance or attempt mismatch.")
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                "Every required job must succeed; skipped/neutral/pending/failing jobs refused.")
    # Catch changes during collection. This is still a point-in-time report, not a lock.
    latest_runs = collection(api, path, "workflow_runs")
    require(len(latest_runs) == 1 and latest_runs[0].get("id") == run_id,
            "CI evidence changed during verification; rerun.")
    validate_run(latest_runs[0], sha, workflow["id"])
    require(latest_runs[0].get("run_attempt") == attempt, "CI rerun started; readiness refused.")
    final = api.get(f"/actions/runs/{run_id}")
    validate_run(final, sha, workflow["id"])
    require(final.get("id") == run_id and final["run_attempt"] == attempt, "CI changed during verification.")
    require(main_tip(api) == sha, "Main moved during verification; start a new run.")
    return {"sha": sha, "run_id": run_id, "attempt": attempt, "jobs": sorted(REQUIRED_JOBS)}


def summary(result):
    url = f"https://github.com/{REPOSITORY}/actions/runs/{result['run_id']}/attempts/{result['attempt']}"
    return ("# Release evidence verified — NO DEPLOYMENT\n\n"
            f"- Exact main commit: `{result['sha']}`\n"
            f"- [Successful CI run and attempt]({url})\n"
            f"- Required jobs: {', '.join(result['jobs'])} — all succeeded\n"
            f"- Checked at: {datetime.now(timezone.utc).isoformat()}\n\n"
            "This is not deployment approval, a deployment trigger, or proof of live health. "
            "The owner must confirm Auto-Deploy Off, approve this exact SHA, check migration/rollback "
            "compatibility and free quotas, then separately confirm a manual Render deployment. "
            "If main or CI changes, run verification again. No provider was contacted.\n")


def main():
    try:
        require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
                os.environ.get("GITHUB_REF") == "refs/heads/main" and
                os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Manual main-only repository context required.")
        result = verify(GitHub(os.environ.get("GH_TOKEN", "")), os.environ.get("RELEASE_SHA", ""),
                        os.environ.get("GITHUB_SHA", ""))
        text = summary(result)
        code = 0
    except NotReady as error:
        text = f"# NOT READY — no deployment\n\n{error}\n\nNo approval or deployment has occurred.\n"
        code = 1
    except Exception:
        # Fail closed without printing arbitrary API content or environment details.
        text = "# NOT READY — no deployment\n\nUnexpected/incomplete evidence; inspect policy tests and retry.\n"
        code = 1
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        Path(os.environ["GITHUB_STEP_SUMMARY"]).write_text(text, encoding="utf-8")
    return code


if __name__ == "__main__":
    sys.exit(main())
