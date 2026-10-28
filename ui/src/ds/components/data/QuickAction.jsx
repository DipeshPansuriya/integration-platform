import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Shortcut card for the dashboard's 2×2 Quick Actions grid. Opens the matching create drawer. */
export function QuickAction({ icon, title, description, tone = 'accent', onClick }) {
  return (
    <button type="button" className={cx('ip-qa', toneClass(tone))} onClick={onClick}>
      <span className="ip-qa-icon" aria-hidden="true"><Icon name={icon} size={16} strokeWidth={1.5} /></span>
      <span>
        <span className="ip-qa-title">{title}</span>
        <span className="ip-qa-desc">{description}</span>
      </span>
    </button>
  );
}

function QuickActionGrid({ children }) {
  return <div className="ip-qa-grid">{children}</div>;
}
QuickAction.Grid = QuickActionGrid;
