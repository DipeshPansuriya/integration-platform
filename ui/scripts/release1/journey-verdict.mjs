// L28 (row 372): the browser journey runner's exit code and one-line verdict. Green needs BOTH a Playwright exit 0
// AND a report that shows at least one test passed and none skipped/fixme'd/failed/flaky (review V1: a fully
// fixme'd journey exits 0 and must never print PASS). A missing report is red. Not the Release 1 gate's line.
export function journeyVerdict(playwrightExit, stats) {
  let reason = '';
  if (playwrightExit !== 0) reason = `playwright exit ${playwrightExit}`;
  else if (!stats) reason = 'no test report';
  else if (!(stats.expected >= 1)) reason = 'zero tests passed';
  else if (stats.skipped > 0 || stats.unexpected > 0 || stats.flaky > 0) reason = `skipped ${stats.skipped}, failed ${stats.unexpected}, flaky ${stats.flaky}`;
  const green = reason === '';
  return { exitCode: green ? 0 : 1, line: `RELEASE 1 BROWSER JOURNEY: ${green ? `PASS (${stats.expected} passed, playwright exit 0)` : `FAIL (${reason})`}` };
}
