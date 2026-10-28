import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** [tone, dot, live] per domain value. The single source of status colour. */
export const STATUS_VOCABULARY = {
  Active: ['ok', true, true], Draft: ['warn', true], Error: ['err', true], Paused: ['muted', true],
  Pending: ['warn'], Processing: ['ok'],
  High: ['err'], Critical: ['err'], Normal: ['muted'], Low: ['muted'],
  OK: ['ok'], Healthy: ['ok'], Slow: ['warn'], Down: ['err'],
  XML: ['ok'], JSON: ['ok'], EDIFACT: ['warn'], CSV: ['muted'],
};

export function resolveStatus(status) {
  if (/^Retry/i.test(String(status))) return { tone: 'err', dot: false, live: false };
  const v = STATUS_VOCABULARY[status];
  return v ? { tone: v[0], dot: !!v[1], live: !!v[2] } : { tone: 'muted', dot: false, live: false };
}

/** Status pill: the word always shows; tone and dot come from the vocabulary unless overridden. */
export function StatusBadge({ status, tone, dot, size = 'md', children, className }) {
  const r = resolveStatus(status);
  const showDot = dot != null ? dot : r.dot;
  return (
    <span className={cx('ip-badge', toneClass(tone || r.tone), r.live && showDot && 'is-live', size === 'xs' && 'ip-badge--xs', className)}>
      {showDot && <span className="ip-badge-dot" aria-hidden="true" />}
      {children || status}
    </span>
  );
}
StatusBadge.vocabulary = STATUS_VOCABULARY;
StatusBadge.resolve = resolveStatus;
