import * as React from 'react';
import { Icon } from '../brand/Icon.jsx';
import { LogoMark } from '../brand/LogoMark.jsx';
import { Button } from '../actions/Button.jsx';

const FEATURES = [
  { icon: 'flows', label: 'Visual flow builder with drag-and-drop' },
  { icon: 'systems', label: 'Multi-system connectivity' },
  { icon: 'rules', label: 'Business rules engine' },
  { icon: 'activity', label: 'Real-time monitoring & alerting' },
];

/** Split sign-in screen: hero with headline and features (hidden at 800px and below), form panel with SSO. */
export function LoginScreen({
  headline = 'Streamline your\ncargo integrations',
  copy = 'Connect systems, transform data, and route messages across your entire cargo handling ecosystem.',
  features = FEATURES,
  title = 'Welcome back',
  subtitle = 'Sign in to your workspace',
  ssoLabel = 'Sign in with Microsoft',
  onSSO,
  altLabel,
  altIcon = 'sign-in',
  onAlt,
}) {
  return (
    <div className="ip-login">
      <section className="ip-login-hero" aria-label="About the platform">
        <div className="ip-login-shapes" aria-hidden="true"><span className="ip-login-shape" /><span className="ip-login-shape" /><span className="ip-login-shape" /></div>
        <div className="ip-login-content">
          <h2 className="ip-login-headline">{headline.split('\n').map((l, i) => <React.Fragment key={i}>{i > 0 && <br />}{l}</React.Fragment>)}</h2>
          <p className="ip-login-copy">{copy}</p>
          <ul className="ip-login-feats">
            {features.map((f) => (
              <li key={f.label} className="ip-login-feat"><span className="ip-login-fi" aria-hidden="true"><Icon name={f.icon} size={16} /></span>{f.label}</li>
            ))}
          </ul>
        </div>
      </section>
      <section className="ip-login-form" aria-label="Sign in">
        <div className="ip-login-card">
          <LogoMark size={44} />
          <h1 className="ip-login-title">{title}</h1>
          <p className="ip-login-sub">{subtitle}</p>
          <Button variant="primary" size="lg" block icon="grid-4" onClick={onSSO}>{ssoLabel}</Button>
          {altLabel && (
            <>
              <div className="ip-login-div">or</div>
              <Button size="lg" block icon={altIcon} onClick={onAlt}>{altLabel}</Button>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
