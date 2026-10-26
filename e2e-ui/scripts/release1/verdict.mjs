// The Release 1 gate runner's exit code and one-line verdict: only a Maven exit 0 is green; anything else
// (a test failure, a signal kill = null) is red, so a caller reading the exit code cannot take a red run for green.
export function gateVerdict(mvnExit) {
  const green = mvnExit === 0;
  return { exitCode: green ? 0 : 1, line: `RELEASE 1 GATE: ${green ? 'PASS' : 'FAIL'} (mvn exit ${mvnExit})` };
}
