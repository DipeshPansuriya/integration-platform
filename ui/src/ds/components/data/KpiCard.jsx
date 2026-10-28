import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** Centred value + label. Sizes: lg (queue stats, 28px), md (report KPIs, 24px), sm (inside drawers, 18px). */
export function KpiCard({ value, label, tone, size = 'md', className }) {
  return (
    <div className={cx('ip-card ip-kpi', 'ip-kpi--' + size, toneClass(tone), className)}>
      <div className="ip-kpi-value">{value}</div>
      <div className="ip-kpi-label">{label}</div>
    </div>
  );
}
