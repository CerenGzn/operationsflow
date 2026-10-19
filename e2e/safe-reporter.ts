import { mkdirSync, writeFileSync } from "node:fs";
import type { Reporter, TestCase, TestResult, FullResult, TestStep } from '@playwright/test/reporter';
// No raw errors, console, attachments, request headers, sources or assertion values.
function reason(message = '') {
  if (message.includes('strict mode violation')) return 'ambiguous locator';
  if (message.includes('Timeout') || message.includes('timeout')) return 'timeout';
  if (message.includes('toBe') || message.includes('toHave')) return 'assertion mismatch';
  return message ? 'other failure' : 'none';
}
export default class SafeReporter implements Reporter {
  private passed = 0;
  private notPassed = 0;
  private timeline: { category: string; sourceLine?: number; durationMs: number; failed: boolean; reason: string }[] = [];
  onStepEnd(_test: TestCase, _result: TestResult, step: TestStep) {
    this.timeline.push({ category: ['test.step', 'pw:api', 'expect', 'fixture', 'hook'].includes(step.category) ? step.category : 'other',
      sourceLine: step.location?.line, durationMs: step.duration, failed: Boolean(step.error), reason: reason(step.error?.message) });
    if (step.error) console.log(`Failure: ${reason(step.error.message)} at source line ${step.location?.line ?? 0}`);
    if (step.category === 'test.step') console.log(`${step.error ? 'FAIL' : 'OK'} step: source line ${step.location?.line ?? 0}`);
  }
  onTestEnd(_test: TestCase, result: TestResult) {
    if (result.status === 'passed') this.passed++; else this.notPassed++;
    console.log(`${result.status}: browser scenario (${result.duration}ms)`);
    if (result.status !== 'passed') console.log('See sanitized action trace and masked screenshot; raw error text withheld.');
  }
  onError() { console.log('Runner error; raw details withheld to protect credentials.'); }
  onEnd(result: FullResult) {
    console.log(`Browser suite: ${result.status}; ${this.passed} passed, ${this.notPassed} not passed`);
    if (result.status !== 'passed' || this.notPassed > 0 || this.passed === 0) {
      mkdirSync('safe-artifacts', { recursive: true });
      writeFileSync('safe-artifacts/action-trace.json', JSON.stringify(this.timeline, null, 2));
      return { status: 'failed' as const };
    }
  }
}
