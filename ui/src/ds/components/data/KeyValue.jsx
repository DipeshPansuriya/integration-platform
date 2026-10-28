import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** A label on the left, a bold value on the right. Use for detail overviews and card facts. */
export function KeyValue({ label, value, mono = false, tone, className }) {
  return (
    <div className={cx('ip-kv', mono && 'is-mono', toneClass(tone), className)}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
