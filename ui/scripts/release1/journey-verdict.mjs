// L28 (row 372): the browser journey runner's exit code and one-line verdict: only a Playwright exit 0 is green
// (a failure, a timeout, a signal kill = null is red). Deliberately not the Release 1 gate's line.
export function journeyVerdict(playwrightExit) {
  const green = playwrightExit === 0;
  return { exitCode: green ? 0 : 1, line: `RELEASE 1 BROWSER JOURNEY: ${green ? 'PASS' : 'FAIL'} (playwright exit ${playwrightExit})` };
}
