import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Dashboard KPI tile: tone strip, icon chip, uppercase label, 30px value, delta line, 9-bar sparkline. */
export function StatTile({ tone = 'accent', icon, label, value, delta, deltaDirection = 'up', sub, spark = [], className }) {
  return (
    <div className={cx('ip-card ip-tile', toneClass(tone), className)}>
      <div className="ip-tile-icon" aria-hidden="true">{icon && <Icon name={icon} size={20} />}</div>
      <div className="ip-tile-label">{label}</div>
      <div className="ip-tile-value">{value}</div>
      {(delta || sub) && (
        <div className="ip-tile-sub">
          {delta && <span className={deltaDirection === 'down' ? 'ip-delta-down' : 'ip-delta-up'}>{delta}</span>}
          {sub}
        </div>
      )}
      {spark.length > 0 && (
        <div className="ip-spark" aria-hidden="true">
          {spark.map((v, i) => <span key={i} style={{ height: Math.max(0, Math.min(100, v)) + '%', animationDelay: (0.08 + i * 0.04).toFixed(2) + 's' }} />)}
        </div>
      )}
    </div>
  );
}
