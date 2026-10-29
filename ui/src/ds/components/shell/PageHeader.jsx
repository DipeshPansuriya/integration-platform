import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Page title (h1) and subtitle on the left, actions on the right: secondary first, one primary last. */
export function PageHeader({ title, subtitle, actions, className }) {
  return (
    <div className={cx('ip-ph', className)}>
      <div>
        <h1 className="ip-ph-title">{title}</h1>
        {subtitle && <p className="ip-ph-sub">{subtitle}</p>}
      </div>
      {actions && <div className="ip-ph-actions">{actions}</div>}
    </div>
  );
}
