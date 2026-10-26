import assert from 'node:assert/strict';
import { test } from 'node:test';
import { gateVerdict } from '../../../scripts/release1/verdict.mjs';

test('verdict: mvn exit 0 is PASS and exit code 0', () => {
  assert.deepEqual(gateVerdict(0), { exitCode: 0, line: 'RELEASE 1 GATE: PASS (mvn exit 0)' });
});

test('verdict: any other mvn exit (failure, signal kill) is FAIL and a non-zero exit code', () => {
  for (const code of [1, 2, 137, null, undefined]) {
    const verdict = gateVerdict(code);
    assert.notEqual(verdict.exitCode, 0, `mvn exit ${code}`);
    assert.match(verdict.line, /^RELEASE 1 GATE: FAIL \(mvn exit /);
  }
});
