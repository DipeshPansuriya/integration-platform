import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** Tinted result box, e.g. the rule tester's "Match: Row 1". Announced politely when it changes. */
export function Callout({ tone = 'ok', title, children, className }) {
  return (
    <div className={cx('ip-callout', toneClass(tone), className)} role="status">
      {title && <div className="ip-callout-title">{title}</div>}
      {children && <div className="ip-callout-body">{children}</div>}
    </div>
  );
}
