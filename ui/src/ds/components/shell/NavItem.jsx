import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** One sidebar link with icon pill and optional count (neutral or danger). */
export function NavItem({ icon, label, count, countTone = 'neutral', active = false, href = '#', onClick, className, ...rest }) {
  return (
    <a href={href} className={cx('ip-nav-item', className)} aria-current={active ? 'page' : undefined} onClick={onClick} {...rest}>
      <span className="ip-nav-pill" aria-hidden="true">{icon && <Icon name={icon} size={14} />}</span>
      <span className="ip-nav-label">{label}</span>
      {count != null && <span className={cx('ip-nav-count', countTone === 'danger' && 'is-danger')}>{count}</span>}
    </a>
  );
}
