import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from './Icon.jsx';

/** The console mark: the Flows glyph on an accent-to-purple tile. 32px in the sidebar, 44px on login. */
export function LogoMark({ size = 32, label = 'iPaaS Console', className }) {
  const large = size >= 40;
  const glyph = large ? Math.round(size / 2) : Math.round(size * 0.5625);
  return (
    <span className={cx('ip-logo', large && 'ip-logo--lg', className)} style={{ width: size, height: size }} role="img" aria-label={label}>
      <Icon name="flows" size={glyph} strokeWidth={1.6} />
    </span>
  );
}
