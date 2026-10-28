// Lane D (same-day build): console sign-in screen - presentational rework of pages/Login.tsx with
// the DS split/hero layout, but the same existing prop set (session state, SSO entry, retry) and the
// same data attributes the page renders. No session/auth logic here: no memory, no auto jump, no
// refs - the SSO and retry buttons only call the handlers the integration passes in. Login.tsx stays
// live until integration; this file borrows its structure verbatim minus the hooks.

import './login.css';

export type LoginView = 'wait' | 'redirect' | 'failed' | 'logged-out' | 'prompt' | 'session-error';

export interface LoginScreenProps {
  state: 'checking' | 'signed-out' | 'error';
  /** Defaults are derived from state: checking -> wait, error -> session-error, else prompt. */
  view?: LoginView;
  onSSO?: () => void;
  onRetry?: () => void;
  className?: string;
}

const HEADINGS: Record<LoginView, string | null> = {
  wait: null,
  redirect: null,
  failed: 'Sign-in did not complete',
  'logged-out': 'You have been signed out',
  prompt: 'Sign in',
  'session-error': 'Sign in',
};

// V4 7e: marketing hero on the left canvas; hidden under 1100px. Feature icon paths are Login.tsx's
// own inline icons (DS Icon is never used here).
function feature(label: string, path: string) {
  return (
    <div className="signin-feature">
      <span className="signin-feature-icon">
        <svg
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d={path} />
        </svg>
      </span>
      {label}
    </div>
  );
}

export function LoginScreen({ state, view, onSSO, onRetry, className }: LoginScreenProps) {
  const v: LoginView = view ?? (state === 'checking' ? 'wait' : state === 'error' ? 'session-error' : 'prompt');
  const heading = HEADINGS[v];

  return (
    <main
      className={'signin' + (className ? ' ' + className : '')}
      data-page="login"
      data-session-state={state}
      data-login-view={v}
    >
      <div className="signin-hero">
        <div>
          <h2>
            Streamline your
            <br />
            cargo integrations
          </h2>
          <p>Connect systems, transform data, and route messages across your cargo handling ecosystem.</p>
          <div className="signin-features">
            {feature('Visual flow builder with drag-and-drop', 'M5 3.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM11 12.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM5 5.5v2.5a2.5 2.5 0 0 0 2.5 2.5H9')}
            {feature('Schema contracts with version tracking', 'M4 1.5h6l3.5 3.5V13a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13V3A1.5 1.5 0 0 1 4 1.5zM10 1.5v3.5h3.5M6 8h4M6 10.5h2')}
            {feature('Decision-table rules engine', 'M8 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z')}
            {feature('Real-time delivery monitoring', 'M1 8h2.5l2-4.5 3 9 2-4.5H14')}
          </div>
        </div>
      </div>
      <div className="signin-column">
        <p className="signin-brand">Integration Platform</p>
        <div className="signin-card">
          {heading && <h1>{heading}</h1>}
          {(v === 'wait' || v === 'redirect') && (
            <p className="signin-lede" role="status" aria-live="polite">
              {v === 'wait' ? 'Checking your session...' : 'Redirecting to sign-in...'}
            </p>
          )}
          {v === 'failed' && (
            <>
              <p className="signin-lede">
                The sign-in was cancelled or did not finish, so nothing was opened. Try again to go to the sign-in
                page.
              </p>
              <button type="button" className="btn btn-primary signin-sso" data-action="sso" onClick={() => onSSO?.()}>
                Try again
              </button>
            </>
          )}
          {(v === 'logged-out' || v === 'prompt') && (
            <>
              <p className="signin-lede">
                {v === 'logged-out'
                  ? 'Your session has ended. Sign in again to continue.'
                  : "Sign in to your tenant's integration workbench."}
              </p>
              <button type="button" className="btn btn-primary signin-sso" data-action="sso" onClick={() => onSSO?.()}>
                Sign in
              </button>
            </>
          )}
          {v === 'session-error' && (
            <div className="signin-error" role="alert">
              <p>
                We could not confirm who you are. Retrying usually fixes this; if it repeats, the sign-in service may
                be down.
              </p>
              <button type="button" className="btn" data-action="retry-session" onClick={() => onRetry?.()}>
                Retry
              </button>
            </div>
          )}
        </div>
      </div>
    </main>
  );
}
export default LoginScreen;
