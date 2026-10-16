// Row 310 lane A (sanjay-developer, T87): the console's in-app sign-in state, #/login (10-screens §2
// layer 1, 00-portal-ia §4). A centered 420px card on the canvas - no side nav, no app shell - with
// ONE primary action, Continue with SSO, the first focus, going to the backend's login entry point
// (LOGIN_ENTRY: Authorization Code + PKCE with Keycloak). While GET /api/v1/me resolves only
// "Checking your session..." shows, so a signed-in user never sees the card flash (App.tsx then
// moves a live session on to the Dashboard); a failed check shows the worded error + Retry.

import { useEffect, useRef } from 'react';
import { LOGIN_ENTRY } from '../api/client';
import './dashboard.css';

export type LoginState = 'checking' | 'signed-out' | 'error';

export function Login({ state, onRetry }: { state: LoginState; onRetry: () => void }) {
  const sso = useRef<HTMLButtonElement>(null);
  const retry = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (state === 'signed-out') sso.current?.focus();
    if (state === 'error') retry.current?.focus();
  }, [state]);

  return (
    <main className="signin" data-page="login" data-session-state={state}>
      <div className="signin-column">
        <p className="signin-brand">Integration Platform</p>
        <div className="signin-card">
          {state !== 'checking' && <h1>Sign in</h1>}
          {state === 'checking' && (
            <p className="signin-lede" aria-live="polite">
              Checking your session...
            </p>
          )}
          {state === 'signed-out' && (
            <>
              <p className="signin-lede">Sign in to your tenant's integration workbench.</p>
              <button
                ref={sso}
                type="button"
                className="btn btn-primary signin-sso"
                data-action="sso"
                onClick={() => window.location.assign(LOGIN_ENTRY)}
              >
                Continue with SSO
              </button>
              <p className="signin-note">You will be redirected to your organisation's sign-in page, then returned here.</p>
            </>
          )}
          {state === 'error' && (
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
