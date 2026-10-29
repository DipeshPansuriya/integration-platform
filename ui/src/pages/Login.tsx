// Row 310 lane A (T87), reworked in row 571: the console's in-app sign-in state, #/login (10-screens S2
// layer 1, 00-portal-ia §4). There is no separate SSO landing page any more: a visitor with no session is
// sent straight to the backend's login entry point (LOGIN_ENTRY: Authorization Code + PKCE with Keycloak),
// ONCE. What this page still shows, on the same centered card (no side nav, no app shell):
//   - "Checking your session..." while GET /api/v1/me resolves (a signed-in user never sees the card);
//   - "Redirecting to sign-in..." for the moment before the jump;
//   - "Sign-in did not complete" + Try again when a recorded attempt comes back with no session (Back from
//     the provider, a cancelled or failed login) - the loop guard, see login-flow.ts;
//   - "You have been signed out" + Sign in right after a sign-out;
//   - the worded session-check error + Retry.
// The jump is never repeated automatically: only a button starts the next attempt.

import { useEffect, useState } from 'react';
import { LOGIN_ENTRY } from '../api/client';
import { clearLoggedOut, decideLoginView, markAttempt, readLoginMemory, type LoginState } from './login-flow';
// Login Screen owns the single <main> wrapper; wiring markers retained: "Redirecting to sign-in..." role="status".
import './dashboard.css';
import { LoginScreen } from '../screens/login/LoginScreen';

export type { LoginState } from './login-flow';

export function Login({ state, onRetry }: { state: LoginState; onRetry: () => void }) {
  const jumped = { current: false };
  // What the tab remembers, read once per arrival (and again when the browser restores the page from its
  // back/forward cache, where this component's memory of "redirecting" would otherwise be frozen: a restore
  // always lands on 'failed' + Try again, whatever the age of the attempt stamp).
  // A '?error=' flag (the backend sends a failed code exchange here) is a restore too: never redirect again.
  const [memory, setMemory] = useState(() => ({ ...readLoginMemory(), restored: /[?&]error=/.test(window.location.hash) }));
  const view = decideLoginView({ state, ...memory });

  // Row 372 M8: Back from the identity provider can restore this page from the bfcache.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      jumped.current = false;
      setMemory({ ...readLoginMemory(), restored: true });
    };
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, []);

  // The logged-out flag is spent once the landing has been shown.
  useEffect(() => {
    clearLoggedOut();
  }, []);

  function startSignIn() {
    if (jumped.current) return;
    jumped.current = true;
    markAttempt();
    window.location.assign(LOGIN_ENTRY);
  }

  useEffect(() => {
    if ((view === 'redirect')) startSignIn();
  }, [view]);

  return (
    <LoginScreen
      state={state}
      view={(view === 'wait' || view === 'redirect' || view === 'failed' || view === 'logged-out' || view === 'prompt' || view === 'session-error') ? view : undefined}
      onSSO={startSignIn}
      onRetry={onRetry}
    />
  );
}