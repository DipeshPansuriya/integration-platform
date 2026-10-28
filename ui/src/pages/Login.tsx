// Row 310 lane A (T87), reworked in row 571: the console's in-app sign-in state, #/login (10-screens §2
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

import { useEffect, useRef, useState } from 'react';
import { LOGIN_ENTRY } from '../api/client';
import { clearLoggedOut, decideLoginView, markAttempt, readLoginMemory, type LoginState } from './login-flow';
import './dashboard.css';

export type { LoginState } from './login-flow';

export function Login({ state, onRetry }: { state: LoginState; onRetry: () => void }) {
  const action = useRef<HTMLButtonElement>(null);
  const retry = useRef<HTMLButtonElement>(null);
  const jumped = useRef(false);
  // What the tab remembers, read once per arrival (and again when the browser restores the page from its
  // back/forward cache, where this component's memory of "redirecting" would otherwise be frozen: a restore
  // always lands on 'failed' + Try again, whatever the age of the attempt stamp).
  const [memory, setMemory] = useState(() => readLoginMemory());
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
    if (view === 'redirect') startSignIn();
  }, [view]);

  useEffect(() => {
    if (view === 'failed' || view === 'logged-out' || view === 'prompt') action.current?.focus();
    if (view === 'session-error') retry.current?.focus();
  }, [view]);

  const heading = {
    wait: null,
    redirect: null,
    failed: 'Sign-in did not complete',
    'logged-out': 'You have been signed out',
    prompt: 'Sign in',
    'session-error': 'Sign in'
  }[view];

  return (
    <main className="signin" data-page="login" data-session-state={state} data-login-view={view}>
      <div className="signin-column">
        <p className="signin-brand">Integration Platform</p>
        <div className="signin-card">
          {heading && <h1>{heading}</h1>}
          {(view === 'wait' || view === 'redirect') && (
            <p className="signin-lede" role="status" aria-live="polite">
              {view === 'wait' ? 'Checking your session...' : 'Redirecting to sign-in...'}
            </p>
          )}
          {view === 'failed' && (
            <>
              <p className="signin-lede">
                The sign-in was cancelled or did not finish, so nothing was opened. Try again to go to the sign-in page.
              </p>
              <button ref={action} type="button" className="btn btn-primary signin-sso" data-action="sso" onClick={startSignIn}>
                Try again
              </button>
            </>
          )}
          {(view === 'logged-out' || view === 'prompt') && (
            <>
              <p className="signin-lede">
                {view === 'logged-out'
                  ? 'Your session has ended. Sign in again to continue.'
                  : "Sign in to your tenant's integration workbench."}
              </p>
              <button ref={action} type="button" className="btn btn-primary signin-sso" data-action="sso" onClick={startSignIn}>
                Sign in
              </button>
            </>
          )}
          {view === 'session-error' && (
            <div className="signin-error" role="alert">
              <p>
                We could not confirm who you are. Retrying usually fixes this; if it repeats, the sign-in service may be
                down.
              </p>
              <button ref={retry} type="button" className="btn" data-action="retry-session" onClick={onRetry}>
                Retry
              </button>
            </div>
          )}
        </div>
      </div>
    </main>
  );
}
