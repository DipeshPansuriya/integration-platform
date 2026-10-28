import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** Status mix ring with the total in the centre and a value legend below. */
export function DonutChart({ segments, total, label = 'Total Flows', size = 150 }) {
  const tot = total != null ? total : segments.reduce((a, s) => a + s.value, 0);
  const k = size / 150;
  const r = 55 * k, sw = 13 * k, c = size / 2, circ = 2 * Math.PI * r;
  let off = 0;
  const arcs = segments.map((s, i) => {
    const dash = tot ? (s.value / tot) * circ : 0;
    const el = (
      <circle key={i} className={cx('ip-donut-seg', toneClass(s.tone))} cx={c} cy={c} r={r} strokeWidth={sw}
        strokeDasharray={dash + ' ' + (circ - dash)} strokeDashoffset={-off} strokeLinecap="round" transform={'rotate(-90 ' + c + ' ' + c + ')'} />
    );
    off += dash;
    return el;
  });
  return (
    <div className="ip-donut">
      <div className="ip-donut-ring" style={{ width: size, height: size }}>
        <svg viewBox={'0 0 ' + size + ' ' + size} width={size} height={size} role="img" aria-label={label + ': ' + tot + '. ' + segments.map((s) => s.label + ' ' + s.value).join(', ')}>{arcs}</svg>
        <div className="ip-donut-center" aria-hidden="true">
          <div className="ip-donut-value">{tot}</div>
          <div className="ip-donut-label">{label}</div>
        </div>
      </div>
      <ul className="ip-legend">
        {segments.map((s, i) => (
          <li key={i} className={toneClass(s.tone)}>
            <span className="ip-legend-sw" aria-hidden="true" />
            {s.label}
            <span className="ip-legend-v">{s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
