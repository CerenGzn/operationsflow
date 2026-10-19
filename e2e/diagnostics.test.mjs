import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import SafeReporter from './safe-reporter.ts';

test('diagnostics retain timing and source lines, never arbitrary trace/error values', () => {
  const cwd = process.cwd(), dir = mkdtempSync(join(tmpdir(), 'e2e-diagnostics-'));
  const log = console.log, output = [];
  try {
    process.chdir(dir); console.log = value => output.push(value);
    const reporter = new SafeReporter();
    const sentinel = 'SENSITIVE_SENTINEL';
    reporter.onStepEnd({}, {}, { category: 'test.step', title: sentinel, location: { file: sentinel, line: 12 },
      duration: 4, error: { message: sentinel }, attachments: [sentinel], params: { password: sentinel } });
    reporter.onTestEnd({ title: sentinel }, { status: 'failed', duration: 5, error: { message: sentinel } });
    reporter.onError({ message: sentinel }); assert.equal(reporter.onEnd({ status: 'failed' }).status, 'failed');
    const trace = readFileSync('safe-artifacts/action-trace.json', 'utf8');
    assert.equal((output.join('\n') + trace).includes(sentinel), false);
    assert.deepEqual(JSON.parse(trace), [{ category: 'test.step', sourceLine: 12, durationMs: 4, failed: true, reason: 'other failure' }]);
  } finally { console.log = log; process.chdir(cwd); rmSync(dir, { recursive: true, force: true }); }
});

test('skipped or empty suite cannot report success', () => {
  const cwd = process.cwd(), dir = mkdtempSync(join(tmpdir(), 'e2e-diagnostics-'));
  const log = console.log;
  try {
    process.chdir(dir); console.log = () => {};
    const empty = new SafeReporter();
    assert.equal(empty.onEnd({ status: 'passed' }).status, 'failed');
    const skipped = new SafeReporter();
    skipped.onTestEnd({}, { status: 'skipped', duration: 0 });
    assert.equal(skipped.onEnd({ status: 'passed' }).status, 'failed');
    const passed = new SafeReporter();
    passed.onTestEnd({}, { status: 'passed', duration: 1 });
    assert.equal(passed.onEnd({ status: 'passed' }), undefined);
  } finally { console.log = log; process.chdir(cwd); rmSync(dir, { recursive: true, force: true }); }
});
