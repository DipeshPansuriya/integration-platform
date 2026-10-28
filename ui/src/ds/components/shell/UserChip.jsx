import * as React from 'react';
import { cx } from '../_shared/util.js';

function initialsOf(name = '') {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
}

/** Signed-in user: gradient avatar with initials, then the first name. Opens the account menu. */
export function UserChip({ name, initials, onClick, className }) {
  return (
    <button type="button" className={cx('ip-user', className)} onClick={onClick} aria-label={'Account: ' + name}>
      <span className="ip-avatar" aria-hidden="true">{initials || initialsOf(name)}</span>
      <span className="ip-user-name">{name}</span>
    </button>
  );
}
