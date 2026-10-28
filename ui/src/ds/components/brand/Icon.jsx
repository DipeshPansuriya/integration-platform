import * as React from 'react';
import { cx } from '../_shared/util.js';

const P = (d) => ['path', { d }];

/**
 * The console's own line icons, copied from the prototype.
 * 16-unit grid, stroke 1.4 (1.5 for actions), currentColor.
 */
export const ICONS = {
  dashboard: [['rect', { x: 1, y: 1, width: 6, height: 6, rx: 1 }], ['rect', { x: 9, y: 1, width: 6, height: 3.5, rx: 1 }], ['rect', { x: 1, y: 9, width: 6, height: 3.5, rx: 1 }], ['rect', { x: 9, y: 6.5, width: 6, height: 6, rx: 1 }]],
  queue: [P('M2.5 8.5 4 3h8l1.5 5.5M2.5 8.5h11v3.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z')],
  failures: [P('M7.3 2.2a.8.8 0 0 1 1.4 0L14 12a.8.8 0 0 1-.7 1.2H2.7a.8.8 0 0 1-.7-1.2z'), P('M8 6v3M8 11v.01')],
  activity: [P('M1 8h2.5l2-4.5 3 9 2-4.5H14')],
  reports: [['rect', { x: 1.5, y: 1.5, width: 13, height: 13, rx: 1 }], P('M5 11V7M8 11V5M11 11V8')],
  flows: [['circle', { cx: 5, cy: 3.5, r: 2 }], ['circle', { cx: 11, cy: 12.5, r: 2 }], P('M5 5.5v2.5a2.5 2.5 0 0 0 2.5 2.5H9')],
  systems: [['rect', { x: 1, y: 5.5, width: 5.5, height: 5.5, rx: 1 }], ['rect', { x: 9.5, y: 5.5, width: 5.5, height: 5.5, rx: 1 }], P('M6.5 8.25h3')],
  contracts: [P('M4 1.5h6l3.5 3.5V13a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13V3A1.5 1.5 0 0 1 4 1.5z'), P('M6 8h4M6 10.5h2')],
  rules: [['circle', { cx: 8, cy: 8, r: 6 }], ['circle', { cx: 8, cy: 8, r: 2.5 }]],
  mappings: [['circle', { cx: 4, cy: 3, r: 1.8 }], ['circle', { cx: 12, cy: 3, r: 1.8 }], ['circle', { cx: 4, cy: 13, r: 1.8 }], ['circle', { cx: 12, cy: 13, r: 1.8 }], P('M5.8 4.5h4.4M5.8 11.5h4.4')],
  'mapping-json': [['circle', { cx: 4, cy: 3, r: 1.8 }], ['circle', { cx: 12, cy: 13, r: 1.8 }], P('M5.8 3h4.4l-4.4 10h4.4')],
  'mapping-xml': [['circle', { cx: 4, cy: 3, r: 1.8 }], ['circle', { cx: 12, cy: 3, r: 1.8 }], P('M5.8 4.5h4.4'), ['circle', { cx: 8, cy: 13, r: 1.8 }], P('M4 4.8v6.4M12 4.8v6.4')],
  document: [['rect', { x: 2, y: 2, width: 12, height: 12, rx: 2 }], P('M6 6h4M6 8h3M6 10h4')],
  rows: [P('M2 4h12M2 8h12M2 12h12')],
  trend: [P('M2 13l5-4 3 2.5 4-5.5')],
  check: [P('M4 8.5l3 3 5-6.5')],
  plus: [P('M8 3v10M3 8h10')],
  close: [P('M4 4l8 8M12 4l-8 8')],
  refresh: [P('M14 8A6 6 0 1 1 8 2'), P('M14 2v6h-6')],
  search: [['circle', { cx: 7, cy: 7, r: 4.5 }], P('m11 11 3.5 3.5')],
  upload: [P('M8 10V3M5 5.5L8 3l3 2.5'), P('M2.5 10v2.5a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V10')],
  'sign-out': [P('M6 13.5H3.5a1.5 1.5 0 0 1-1.5-1.5V4a1.5 1.5 0 0 1 1.5-1.5H6M10.5 11l3-3-3-3M5.5 8h8')],
  'sign-in': [P('M10 13.5h2.5a1.5 1.5 0 0 0 1.5-1.5V4a1.5 1.5 0 0 0-1.5-1.5H10M5.5 5l-3 3 3 3M2.5 8h8')],
  'grid-4': [['rect', { x: 1.5, y: 1.5, width: 5.5, height: 5.5, rx: 0.5 }], ['rect', { x: 9, y: 1.5, width: 5.5, height: 5.5, rx: 0.5 }], ['rect', { x: 1.5, y: 9, width: 5.5, height: 5.5, rx: 0.5 }], ['rect', { x: 9, y: 9, width: 5.5, height: 5.5, rx: 0.5 }]],
  'chevron-down': { vb: '0 0 12 12', d: [P('M3 5l3 3 3-3')] },
  arrow: { vb: '0 0 20 12', d: [P('M2 6h12M12 2l4 4-4 4')] },
  moon: { vb: '0 0 20 20', fill: true, d: [P('M17.5 10.66A7.5 7.5 0 1 1 9.34 2.5a5.83 5.83 0 0 0 8.16 8.16z')] },
  sun: { vb: '0 0 20 20', d: [['circle', { cx: 10, cy: 10, r: 4, fill: 'currentColor', stroke: 'none' }], P('M10 1v2m0 14v2m-9-9h2m14 0h2m-3.5-6.5-1.4 1.4m-9.2 9.2-1.4 1.4m0-12L4.9 4.9m9.2 9.2 1.4 1.4')] },
  palette: { vb: '0 0 20 20', d: [['circle', { cx: 10, cy: 10, r: 8 }], ['circle', { cx: 10, cy: 5, r: 1.5, stroke: 'none', style: { fill: 'var(--accent)' } }], ['circle', { cx: 5, cy: 12, r: 1.5, stroke: 'none', style: { fill: 'var(--ok)' } }], ['circle', { cx: 15, cy: 12, r: 1.5, stroke: 'none', style: { fill: 'var(--err)' } }]] },
};
ICONS.menu = ICONS.rows;
ICONS.warning = ICONS.failures;

/** Inline SVG icon by name; inherits currentColor. Decorative unless `title` is given. */
export function Icon({ name, size = 16, strokeWidth, title, className, style }) {
  const def = ICONS[name];
  if (!def) return null;
  const spec = Array.isArray(def) ? { vb: '0 0 16 16', d: def } : def;
  const [w, h] = Array.isArray(size) ? size : [size, size];
  const sw = strokeWidth != null ? strokeWidth : spec.vb === '0 0 16 16' ? 1.4 : 1.5;
  return React.createElement(
    'svg',
    {
      width: w, height: h, viewBox: spec.vb,
      fill: spec.fill ? 'currentColor' : 'none',
      stroke: spec.fill ? 'none' : 'currentColor',
      strokeWidth: sw, strokeLinecap: 'round', strokeLinejoin: 'round',
      className: cx('ip-icon', className), style,
      role: title ? 'img' : undefined,
      'aria-hidden': title ? undefined : true,
      focusable: 'false',
    },
    title ? React.createElement('title', null, title) : null,
    spec.d.map(([tag, attrs], i) => React.createElement(tag, { key: i, ...attrs }))
  );
}
