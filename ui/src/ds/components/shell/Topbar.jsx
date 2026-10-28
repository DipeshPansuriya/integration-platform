import * as React from 'react';
import { cx } from '../_shared/util.js';
import { IconButton } from '../actions/IconButton.jsx';

/** 54px sticky top bar: product name, divider, search slot, right-hand cluster. `onMenu` adds the mobile menu button. */
export function Topbar({ product = 'Integration Platform', search, children, onMenu, className }) {
  return (
    <header className={cx('ip-topbar', className)}>
      {onMenu && <IconButton className="ip-topbar-menu" icon="menu" label="Open navigation" onClick={onMenu} />}
      <span className="ip-topbar-product">{product}</span>
      <span className="ip-topbar-sep" aria-hidden="true" />
      {search}
      <div className="ip-topbar-right">{children}</div>
    </header>
  );
}
