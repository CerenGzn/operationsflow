"""Offline policy/transport tests. No API, account or deployment calls."""
import copy
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("readiness", ROOT / "scripts/release_readiness.py")
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)
SHA = "a" * 40
OTHER = "b" * 40


class Fixture:
    def __init__(self):
        self.sha = SHA
        self.workflow = {"id": 7, "path": r.WORKFLOW, "state": "active"}
        self.run = {"id": 42, "head_sha": SHA, "head_branch": "main", "event": "push",
                    "workflow_id": 7, "path": r.WORKFLOW, "status": "completed", "conclusion": "success",
                    "run_attempt": 2, "repository": {"full_name": r.REPOSITORY},
                    "head_repository": {"full_name": r.REPOSITORY}}
        self.jobs = [{"id": i, "name": name, "run_id": 42, "head_sha": SHA, "run_attempt": 2,
                      "status": "completed", "conclusion": "success"}
                     for i, name in enumerate(sorted(r.REQUIRED_JOBS), 1)]
        self.extra_runs = []
        self.calls = []
        self.change = None

    def get(self, path):
        self.calls.append(path)
        if self.change:
            self.change(self, path)
        if path == "/git/ref/heads/main":
            return {"ref": "refs/heads/main", "object": {"type": "commit", "sha": self.sha}}
        if path == "/actions/workflows/ci.yml":
            return copy.deepcopy(self.workflow)
        if path.startswith("/actions/workflows/7/runs?"):
            return {"total_count": 1 + len(self.extra_runs), "workflow_runs": copy.deepcopy([self.run] + self.extra_runs)}
        if path == "/actions/runs/42":
            return copy.deepcopy(self.run)
        if path.startswith("/actions/runs/42/attempts/2/jobs?"):
            return {"total_count": len(self.jobs), "jobs": copy.deepcopy(self.jobs)}
        raise AssertionError("Unexpected fixture request: " + path)


class PolicyTests(unittest.TestCase):
    def test_success_uses_exact_attempt_and_reports_no_deployment(self):
        api = Fixture()
        result = r.verify(api, SHA, SHA)
        self.assertEqual(result, {"sha": SHA, "run_id": 42, "attempt": 2, "jobs": sorted(r.REQUIRED_JOBS)})
        self.assertTrue(any("/attempts/2/jobs?" in path for path in api.calls))
        text = r.summary(result)
        self.assertIn("NO DEPLOYMENT", text)
        self.assertIn(SHA, text)
        self.assertIn("/runs/42/attempts/2", text)
        self.assertIn("not deployment approval", text)

    def test_malformed_and_injected_sha_rejected_without_api_calls(self):
        for sha in ["main", SHA[:8], SHA.upper(), OTHER + "\n", "$(curl example.invalid)", "", "x" * 40]:
            with self.subTest(sha=sha):
                api = Fixture()
                with self.assertRaises(r.NotReady): r.verify(api, sha, sha)
                self.assertEqual(api.calls, [])

    def test_different_execution_snapshot_rejected_without_api_calls(self):
        api = Fixture()
        with self.assertRaises(r.NotReady): r.verify(api, SHA, OTHER)
        self.assertEqual(api.calls, [])

    def test_only_current_main_tip_is_eligible(self):
        api = Fixture(); api.sha = OTHER
        with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_main_moving_during_verification_refuses(self):
        api = Fixture()
        def move(a, path):
            if path == "/git/ref/heads/main" and a.calls.count(path) == 2: a.sha = OTHER
        api.change = move
        with self.assertRaisesRegex(r.NotReady, "Main moved"): r.verify(api, SHA, SHA)

    def test_run_identity_and_workflow_provenance(self):
        cases = {"head_sha": OTHER, "head_branch": "feature/untrusted", "event": "pull_request",
                 "workflow_id": 8, "path": ".github/workflows/other.yml", "run_attempt": 0,
                 "repository": {"full_name": "untrusted/repo"}, "head_repository": {"full_name": "untrusted/repo"}}
        for key, value in cases.items():
            with self.subTest(field=key):
                api = Fixture(); api.run[key] = value
                with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_failed_pending_neutral_skipped_cancelled_ci_refused(self):
        for state, conclusion in [("queued", None), ("in_progress", None), ("completed", "failure"),
                                  ("completed", "skipped"), ("completed", "neutral"), ("completed", "cancelled"),
                                  ("completed", "timed_out"), ("completed", "action_required")]:
            with self.subTest(state=state, conclusion=conclusion):
                api = Fixture(); api.run.update(status=state, conclusion=conclusion)
                with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_missing_or_multiple_runs_refused(self):
        api = Fixture(); api.extra_runs = [copy.deepcopy(api.run)]
        with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)
        class Missing(Fixture):
            def get(self, path):
                if "/runs?" in path: return {"total_count": 0, "workflow_runs": []}
                return super().get(path)
        with self.assertRaises(r.NotReady): r.verify(Missing(), SHA, SHA)

    def test_disabled_wrong_or_missing_workflow_refused(self):
        for value in [{}, {"id": 7, "state": "disabled_manually", "path": r.WORKFLOW},
                      {"id": 7, "state": "active", "path": "other.yml"}]:
            api = Fixture(); api.workflow = value
            with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_every_job_must_succeed(self):
        for name in sorted(r.REQUIRED_JOBS):
            for state, conclusion in [("completed", "skipped"), ("completed", "failure"),
                                      ("completed", "neutral"), ("in_progress", None)]:
                with self.subTest(job=name, state=state, conclusion=conclusion):
                    api = Fixture()
                    next(j for j in api.jobs if j["name"] == name).update(status=state, conclusion=conclusion)
                    with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_missing_extra_duplicate_or_foreign_jobs_refused(self):
        for change in [lambda j: j.pop(), lambda j: j.append(copy.deepcopy(j[0])),
                       lambda j: j[0].update(name="unexpected"), lambda j: j[0].update(head_sha=OTHER),
                       lambda j: j[0].update(run_attempt=1), lambda j: j[0].update(run_id=43),
                       lambda j: j[0].update(id=j[1]["id"])]:
            api = Fixture(); change(api.jobs)
            with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_rerun_detected_before_and_after_jobs(self):
        for occurrence in [1, 2]:
            api = Fixture()
            def rerun(a, path):
                if path == "/actions/runs/42" and a.calls.count(path) == occurrence:
                    a.run.update(run_attempt=3, status="in_progress", conclusion=None)
            api.change = rerun
            with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_second_ci_run_appearing_refused(self):
        api = Fixture()
        def duplicate(a, path):
            if "/runs?" in path and a.calls.count(path) == 2: a.extra_runs = [copy.deepcopy(a.run)]
        api.change = duplicate
        with self.assertRaises(r.NotReady): r.verify(api, SHA, SHA)

    def test_complete_pagination(self):
        class Pages:
            def get(self, path):
                return {"total_count": 101, "jobs": list(range(100)) if path.endswith("&page=1") else [100]}
        self.assertEqual(r.collection(Pages(), "/sample", "jobs"), list(range(101)))

    def test_truncated_inconsistent_or_excessive_pagination(self):
        for data in [{"total_count": 1, "jobs": []}, {"total_count": 1001, "jobs": []},
                     {"total_count": 0, "jobs": [1]}, {"total_count": "1", "jobs": [1]}, {}]:
            class Page:
                def get(self, path): return data
            with self.assertRaises(r.NotReady): r.collection(Page(), "/sample", "jobs")
        class Changed:
            def get(self, path):
                return {"total_count": 101, "jobs": list(range(100))} if path.endswith("&page=1") else {"total_count": 102, "jobs": [100, 101]}
        with self.assertRaises(r.NotReady): r.collection(Changed(), "/sample", "jobs")


class TransportTests(unittest.TestCase):
    def test_transport_only_uses_get_to_fixed_repository(self):
        api = r.GitHub("synthetic-test-token")
        with patch.object(api.opener, "open") as call:
            call.return_value.__enter__.return_value.status = 200
            call.return_value.__enter__.return_value.read.return_value = b'{"ok": true}'
            self.assertEqual(api.get("/git/ref/heads/main"), {"ok": True})
            request = call.call_args.args[0]
            self.assertEqual(request.get_method(), "GET")
            self.assertEqual(request.full_url, "https://api.github.com/repos/CerenGzn/operationsflow/git/ref/heads/main")

    def test_redirect_refused(self):
        with self.assertRaises(r.NotReady): r.NoRedirects().redirect_request(None, None, 302, "", {}, "https://example.invalid")

    def test_api_errors_do_not_echo_tokens_or_response_bodies(self):
        for error in [HTTPError("https://api.github.com", 403, "sensitive-body", {}, None),
                      URLError("sensitive-body"), ValueError("sensitive-body")]:
            api = r.GitHub("synthetic-test-token")
            with patch.object(api.opener, "open", side_effect=error):
                with self.assertRaises(r.NotReady) as caught: api.get("/actions/runs/42")
                self.assertNotIn("sensitive-body", str(caught.exception))
                self.assertNotIn("synthetic-test-token", str(caught.exception))

    def test_invalid_execution_context_never_calls_api(self):
        for context in [{}, {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/heads/main", "GITHUB_REPOSITORY": r.REPOSITORY},
                        {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/feature", "GITHUB_REPOSITORY": r.REPOSITORY}]:
            with patch.dict(os.environ, context, clear=True), patch.object(r, "GitHub") as api, patch("sys.stdout", new_callable=io.StringIO) as output:
                self.assertEqual(r.main(), 1)
                api.assert_not_called()
                self.assertIn("NOT READY", output.getvalue())

    def test_missing_token_refused(self):
        with self.assertRaises(r.NotReady): r.GitHub("")


if __name__ == "__main__":
    unittest.main()

class ReleaseTests(unittest.TestCase):
    def fixture(self):
        primary, browser = Fixture(), Fixture()
        browser.workflow['path'] = '.github/workflows/e2e.yml'
        browser.run['path'] = '.github/workflows/e2e.yml'
        browser.jobs = [dict(browser.jobs[0], name='browser-e2e')]
        class Combined:
            selected = primary
            def get(self, path):
                if path == '/actions/workflows/ci.yml': self.selected = primary
                if path == '/actions/workflows/e2e.yml':
                    self.selected = browser
                    path = '/actions/workflows/ci.yml'
                return self.selected.get(path)
        return Combined(), primary, browser

    def test_both_workflows_required(self):
        api, _, _ = self.fixture()
        result = r.verify_release(api, SHA, SHA)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[1]['jobs'], ['browser-e2e'])

    def test_browser_failure_pending_skipped_or_wrong_provenance_refused(self):
        for key, value in [('conclusion','failure'), ('conclusion','skipped'),
                           ('status','in_progress'), ('head_sha',OTHER), ('event','pull_request')]:
            with self.subTest(key=key, value=value):
                api, _, browser = self.fixture(); browser.run[key] = value
                with self.assertRaises(r.NotReady): r.verify_release(api, SHA, SHA)
        for mutate in [lambda b: b.jobs.clear(), lambda b: b.extra_runs.append(copy.deepcopy(b.run)),
                       lambda b: b.jobs[0].update(conclusion='skipped')]:
            api, _, browser = self.fixture(); mutate(browser)
            with self.assertRaises(r.NotReady): r.verify_release(api, SHA, SHA)

    def test_primary_rerun_during_browser_verification_refused(self):
        api, primary, browser = self.fixture()
        def change(b, path): primary.run['status'] = 'in_progress'
        browser.change = change
        with self.assertRaises(r.NotReady): r.verify_release(api, SHA, SHA)

    def test_no_optional_bypass_for_browser_workflow(self):
        self.assertEqual(r.RELEASE_WORKFLOWS, ((r.WORKFLOW, r.REQUIRED_JOBS),
            ('.github/workflows/e2e.yml', frozenset({'browser-e2e'}))))
