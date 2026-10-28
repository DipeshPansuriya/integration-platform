import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Glass panel with a title row (title + aside slot) over flush or padded content. */
export function Panel({ title, aside, padded = false, className, children }) {
  const id = React.useId();
  return (
    <section className={cx('ip-card ip-panel', className)} aria-labelledby={title ? id : undefined}>
      {(title || aside) && (
        <header className="ip-panel-h">
          {title && <h3 className="ip-panel-title" id={id}>{title}</h3>}
          {aside}
        </header>
      )}
      <div className={cx('ip-panel-body', padded && 'is-padded')}>{children}</div>
    </section>
  );
}
