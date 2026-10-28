import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** Vertical event log, newest first. Item tone: ok (success), err (failure), warn (threshold), info (user or system action). */
export function Timeline({ items, compact = false, label = 'Activity' }) {
  return (
    <ol className={cx('ip-timeline', compact && 'is-compact')} aria-label={label}>
      {items.map((it, i) => (
        <li key={i} className={cx('ip-tl-item', toneClass(it.tone || 'info'))}>
          <span className="ip-tl-dot" aria-hidden="true" />
          <div className="ip-tl-time">{it.time}</div>
          <div className="ip-tl-text">{it.text}</div>
        </li>
      ))}
    </ol>
  );
}
