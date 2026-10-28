// Row 571: the console no longer asks "Continue with SSO". A visitor with no session goes straight to the
// identity provider (LOGIN_ENTRY: Authorization Code + PKCE, state and nonce are the backend's), and this
// module holds the three small decisions around that jump - none of them touches a token:
//   1. decideLoginView: auto-redirect ONCE; a return to #/login that still has no session after a recorded
//      attempt (Back from the provider, a cancelled or failed login) shows "Try again" - never a second
//      automatic redirect (the loop guard). Right after a sign-out the landing shows a Sign in button.
//   2. the return-to path: the screen a visitor asked for (a #/... hash) is remembered across the
//      provider round trip and restored once signed in. Hash routes only - never a URL, so it cannot
//      become an open redirect.
//   3. sessionStorage holds only the attempt time, a logged-out flag and that hash (per tab, no secret).

export type LoginState = 'checking' | 'signed-out' | 'error';
export type LoginView = 'wait' | 'redirect' | 'failed' | 'logged-out' | 'prompt' | 'session-error';

export const ATTEMPT_KEY = 'ip.loginAttempt';
export const LOGGED_OUT_KEY = 'ip.loggedOut';
export const RETURN_KEY = 'ip.returnTo';
/** An attempt younger than this that comes back signed-out is a failed/cancelled login, not a first visit. */
export const ATTEMPT_TTL_MS = 10 * 60 * 1000;

const RETURN_PATTERN = /^#\/[A-Za-z0-9._~%!$&'()*+,;=:@/-]*$/;
const MAX_RETURN_LENGTH = 512;

export type LoginMemory = { attemptAt: number | null; loggedOut: boolean; storageOk: boolean; now: number };

/** What to show at #/login. Pure: every input is passed in. */
export function decideLoginView(input: { state: LoginState } & LoginMemory): LoginView {
  if (input.state === 'checking') return 'wait';
  if (input.state === 'error') return 'session-error';
  if (input.loggedOut) return 'logged-out';
  // Without storage the loop guard cannot work, so a button decides - never an automatic jump.
  if (!input.storageOk) return 'prompt';
  if (input.attemptAt !== null && input.now - input.attemptAt < ATTEMPT_TTL_MS) return 'failed';
  return 'redirect';
}

/** A hash worth coming back to: a real screen route, not the sign-in state, not a stray value. */
export function sanitizeReturnTo(hash: string | null | undefined): string | null {
  if (!hash || hash.length > MAX_RETURN_LENGTH) return null;
  if (!RETURN_PATTERN.test(hash)) return null;
  if (hash === '#/' || hash.startsWith('#/login')) return null;
  return hash;
}

/** Where to move after a sign-in: only from the bare console address, and only to a remembered safe hash. */
export function landingAfterLogin(currentHash: string, returnTo: string | null): string | null {
  const safe = sanitizeReturnTo(returnTo);
  if (!safe) return null;
  return currentHash === '' || currentHash === '#' || currentHash === '#/' ? safe : null;
}

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function store(): Store | null {
  try {
    const s = (globalThis as { sessionStorage?: Store }).sessionStorage;
    if (!s) return null;
    s.getItem(ATTEMPT_KEY); // blocked storage throws here, not at the property read
    return s;
  } catch {
    return null;
  }
}

function guarded<T>(fallback: T, run: (s: Store) => T): T {
  const s = store();
  if (!s) return fallback;
  try {
    return run(s);
  } catch {
    return fallback;
  }
}

export function readLoginMemory(now: number = Date.now()): LoginMemory {
  const storageOk = store() !== null;
  const raw = guarded<string | null>(null, (s) => s.getItem(ATTEMPT_KEY));
  const parsed = raw === null ? NaN : Number(raw);
  return {
    attemptAt: Number.isFinite(parsed) ? parsed : null,
    loggedOut: guarded(false, (s) => s.getItem(LOGGED_OUT_KEY) === '1'),
    storageOk,
    now
  };
}

/** Recorded just before the jump to the provider; a return to #/login with this set and no session = failed. */
export function markAttempt(now: number = Date.now()): void {
  guarded(undefined, (s) => {
    s.removeItem(LOGGED_OUT_KEY);
    s.setItem(ATTEMPT_KEY, String(now));
  });
}

/** A live session proves the attempt worked. */
export function clearAttempt(): void {
  guarded(undefined, (s) => s.removeItem(ATTEMPT_KEY));
}

export function markLoggedOut(): void {
  guarded(undefined, (s) => {
    s.setItem(LOGGED_OUT_KEY, '1');
    s.removeItem(ATTEMPT_KEY);
    s.removeItem(RETURN_KEY); // the next person at this tab does not inherit the last one's screen
  });
}

export function clearLoggedOut(): void {
  guarded(undefined, (s) => s.removeItem(LOGGED_OUT_KEY));
}

export function rememberReturnTo(hash: string | null | undefined): void {
  const safe = sanitizeReturnTo(hash);
  if (safe) guarded(undefined, (s) => s.setItem(RETURN_KEY, safe));
}

/** Reads and forgets the remembered hash (one use). */
export function takeReturnTo(): string | null {
  return guarded<string | null>(null, (s) => {
    const value = s.getItem(RETURN_KEY);
    s.removeItem(RETURN_KEY);
    return value;
  });
}
