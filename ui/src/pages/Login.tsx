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
  // back/forward cache, where this component's memory of "redirecting" would otherwise be frozen).
  const [memory, setMemory] = useState(() => readLoginMemory());
  const view = decideLoginView({ state, ...memory });

  // Row 372 M8: Back from the identity provider can restore this page from the bfcache.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      jumped.current = false;
      setMemory(readLoginMemory());
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
      {/* V4 7e: marketing hero on the left canvas; hidden under 1100px. */}
      <div className="signin-hero">
        <div>
          <h2>Streamline your<br />cargo integrations</h2>
          <p>Connect systems, transform data, and route messages across your cargo handling ecosystem.</p>
          <div className="signin-features">
            <div className="signin-feature">
              <span className="signin-feature-icon">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 3.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM11 12.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM5 5.5v2.5a2.5 2.5 0 0 0 2.5 2.5H9" /></svg>
              </span>
              Visual flow builder with drag-and-drop
            </div>
            <div className="signin-feature">
              <span className="signin-feature-icon">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M4 1.5h6l3.5 3.5V13a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13V3A1.5 1.5 0 0 1 4 1.5zM10 1.5v3.5h3.5M6 8h4M6 10.5h2" /></svg>
              </span>
              Schema contracts with version tracking
            </div>
            <div className="signin-feature">
              <span className="signin-feature-icon">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M8 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z" /></svg>
              </span>
              Decision-table rules engine
            </div>
            <div className="signin-feature">
              <span className="signin-feature-icon">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M1 8h2.5l2-4.5 3 9 2-4.5H14" /></svg>
              </span>
              Real-time delivery monitoring
            </div>
          </div>
        </div>
      </div>
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
