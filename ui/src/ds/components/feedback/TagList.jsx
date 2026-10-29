import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Wrapped row of accent-tinted tags: labels, or "Used by" references (mono). */
export function TagList({ tags, mono = false, label }) {
  return (
    <ul className={cx('ip-tags', mono && 'is-mono')} aria-label={label}>
      {tags.map((t) => <li key={t} className="ip-tag">{t}</li>)}
    </ul>
  );
}
