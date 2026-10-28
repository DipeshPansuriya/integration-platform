import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

const HEALTH = { Healthy: 'ok', Slow: 'warn', Down: 'err' };

/** One system in the health list: glowing dot, name, mono latency, status word. */
export function HealthRow({ name, latency, status = 'Healthy' }) {
  return (
    <div className={cx('ip-health', toneClass(HEALTH[status] || 'muted'))}>
      <span className="ip-health-dot" aria-hidden="true" />
      <span className="ip-health-name">{name}</span>
      <span className="ip-health-lat">{latency}</span>
      <span className="ip-health-status">{status}</span>
    </div>
  );
}
