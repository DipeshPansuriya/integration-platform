import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Action button: secondary (default), primary (one per header or footer, placed last), danger. */
export function Button({ variant = 'secondary', size = 'md', icon, block = false, children, className, type = 'button', ...rest }) {
  return (
    <button
      type={type}
      className={cx('ip-btn', 'ip-btn--' + variant, size !== 'md' && 'ip-btn--' + size, block && 'ip-btn--block', className)}
      {...rest}
    >
      {icon && <Icon name={icon} size={size === 'lg' ? 16 : 14} strokeWidth={1.5} />}
      {children}
    </button>
  );
}
