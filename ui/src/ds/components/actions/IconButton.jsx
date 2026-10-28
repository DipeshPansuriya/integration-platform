import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Square 34px icon-only button. `label` is required: it becomes the accessible name. */
export function IconButton({ icon, label, className, children, type = 'button', ...rest }) {
  return (
    <button type={type} className={cx('ip-iconbtn', className)} aria-label={label} title={label} {...rest}>
      {children || <Icon name={icon} size={16} />}
    </button>
  );
}
