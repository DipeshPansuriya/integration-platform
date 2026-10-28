import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Frosted glass card, the base surface for tiles, panels and entity cards. */
export function Card({ as: Tag = 'div', padded = true, interactive = false, className, children, ...rest }) {
  return (
    <Tag className={cx('ip-card', padded && 'is-padded', interactive && 'is-interactive', className)} {...rest}>
      {children}
    </Tag>
  );
}
