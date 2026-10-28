import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Full-height console frame: sidebar, then topbar over the scrolling content. At 800px and below the sidebar becomes an off-canvas panel driven by `navOpen`. */
export function AppShell({ sidebar, topbar, children, navOpen = false, onNavClose, className }) {
  return (
    <div className={cx('ip-shell', navOpen && 'is-nav-open', className)}>
      {sidebar}
      {navOpen && <div className="ip-nav-scrim" onClick={onNavClose} aria-hidden="true" />}
      <div className="ip-main">
        {topbar}
        <main className="ip-content">{children}</main>
      </div>
    </div>
  );
}
