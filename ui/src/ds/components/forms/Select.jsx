import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Native select styled like Input, with a currentColor chevron. options: strings or { value, label }. */
export function Select({ options = [], className, ...rest }) {
  return (
    <span className="ip-select-wrap">
      <select className={cx('ip-input ip-select', className)} {...rest}>
        {options.map((o) => (typeof o === 'string'
          ? <option key={o}>{o}</option>
          : <option key={o.value} value={o.value}>{o.label}</option>))}
      </select>
      <Icon name="chevron-down" size={12} strokeWidth={1.5} />
    </span>
  );
}
