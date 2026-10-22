// E2E demo mode (test code only): the base suite's real specs, run HEADED and slowed down so a person can follow them.
// E2E_DEMO=1 turns it on; E2E_DEMO_SLOWMO_MS (default 700, 0..5000) is the pause before every browser action.
// Off by default: without E2E_DEMO every launch is the headless fast one the suites always used.

const DEFAULT_SLOWMO_MS = 700;

/** How long a narrated beat stays on screen: 0 normally, E2E_DEMO_PAUSE_MS (default 4000, 0..30000) in demo mode. */
export function demoPauseMs(vars = process.env) {
  if (vars.E2E_DEMO !== '1') return 0;
  const raw = vars.E2E_DEMO_PAUSE_MS;
  const ms = raw === undefined || raw === '' ? 4000 : Number(raw);
  if (!Number.isInteger(ms) || ms < 0 || ms > 30000) {
    throw new Error(`E2E_DEMO_PAUSE_MS must be a whole number of milliseconds from 0 to 30000, got ${raw}`);
  }
  return ms;
}

/** Chromium launch options for the given variables: {} normally, headed + slowMo in demo mode. */
export function demoLaunchOptions(vars = process.env) {
  if (vars.E2E_DEMO !== '1') return {};
  const raw = vars.E2E_DEMO_SLOWMO_MS;
  const slowMo = raw === undefined || raw === '' ? DEFAULT_SLOWMO_MS : Number(raw);
  if (!Number.isInteger(slowMo) || slowMo < 0 || slowMo > 5000) {
    throw new Error(`E2E_DEMO_SLOWMO_MS must be a whole number of milliseconds from 0 to 5000, got ${raw}`);
  }
  return { headless: false, slowMo, args: ['--window-size=1400,900'] };
}
