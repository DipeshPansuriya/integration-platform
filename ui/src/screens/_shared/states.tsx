// Lane A shared screen states: skeleton blocks (muted, final-size), the empty card, the error
// Callout with Retry, and the confirm dialog. The DS has no Skeleton and no Dialog component yet -
// these close that gap once for the three lane-A screens (noted for the wiring hand-back).
//
// Harness-bundle note (for the wiring hand-back): the test react stub exports no `createElement`
// and no `useLayoutEffect`, so esbuild folds ds `Icon` and ds `LineChart` body calls to `(void 0)`.
// Until the stub is widened, the three components here (LineChart, IconButton, QuickAction) are
// lane-local replicas of the ds markup built from the ds ICONS data - same classes, same type names.
import type { ReactElement, ReactNode } from 'react';
import { useId } from 'react';
// Direct per-module ds imports (barrel pulls forms/* whose top-level forwardRef crashes the
// test-harness react stub; see lane hand-back notes).
import { Button } from '../../ds/components/actions/Button.jsx';
import { Callout } from '../../ds/components/feedback/Callout.jsx';
// util.js has no .d.ts (JS module); load through namespace and cast once.
// @ts-ignore
import * as utilModule from '../../ds/components/_shared/util.js';
const { toneClass } = utilModule as any;
// @ts-ignore
import * as iconModule from '../../ds/components/brand/Icon.jsx';
const ICONS = (iconModule as any).ICONS as Record<string, any>;
import type { IconName } from '../../ds/components/brand/Icon.jsx';
import type { Tone } from '../../ds/components/feedback/StatusBadge.jsx';
import './states.css';

export function SkeletonBlock(props: { height?: number; className?: string }): ReactElement {
  const style = props.height === undefined ? undefined : { height: props.height };
  const cls = ['scr-skel', props.className].filter(Boolean).join(' ');
  return <div className={cls} style={style} data-test="skeleton-block" />;
}

export function EmptyState(props: { title: string; action?: ReactNode }): ReactElement {
  return (
    <div className="scr-empty" data-test="empty">
      <p className="scr-empty-text">{props.title}</p>
      {props.action ? <div className="scr-empty-action">{props.action}</div> : null}
    </div>
  );
}

export function ErrorNote(props: {
  what: string; message?: string | null; stale?: boolean; onReload?: () => void;
}): ReactElement {
  return (
    <div className="scr-error" data-test="error">
      <Callout tone="err" title={`Could not load ${props.what}`}>
        {props.message ? <p className="scr-error-message-line">{props.message}</p> : null}
        {props.stale ? <p className="scr-stale" data-test="stale">Showing the last loaded data (stale).</p> : null}
        <Button variant="secondary" size="sm" onClick={props.onReload}>Retry</Button>
      </Callout>
    </div>
  );
}

export function ConfirmDialog(props: {
  open: boolean; title: string; body: string; confirmLabel: string;
  confirmVariant?: 'danger' | 'primary'; onConfirm?: () => void; onCancel?: () => void;
}): ReactElement | null {
  if (!props.open) return null;
  return (
    <div className="scr-overlay" data-test="confirm-overlay" onClick={props.onCancel}>
      <div className="scr-dialog" role="dialog" aria-modal="true" aria-label={props.title} onClick={(e) => e.stopPropagation()}>
        <h2 className="scr-dialog-title">{props.title}</h2>
        <p className="scr-dialog-body">{props.body}</p>
        <div className="scr-dialog-footer">
          <Button variant="secondary" onClick={props.onCancel}>Cancel</Button>
          <Button variant={props.confirmVariant ?? 'primary'} onClick={props.onConfirm}>{props.confirmLabel}</Button>
        </div>
      </div>
    </div>
  );
}

// --- Lane-local DS replicas (harness react-stub gap; see header note) ---

/** Same output as ds Icon, built from the ds ICONS data without React.createElement. */
export function IconGlyph(props: { name?: IconName; size?: number; strokeWidth?: number }): ReactElement | null {
  const raw = props.name == null ? undefined : ICONS[props.name];
  if (raw == null) return null;
  const spec = Array.isArray(raw) ? { vb: '0 0 16 16', d: raw, fill: false } : raw;
  const size = props.size ?? 16;
  const sw = props.strokeWidth != null ? props.strokeWidth : spec.vb === '0 0 16 16' ? 1.4 : 1.5;
  const shapes = spec.d.map(([tag, attrs]: [string, Record<string, unknown>], i: number) => {
    if (tag === 'path') return <path key={i} {...attrs} />;
    if (tag === 'rect') return <rect key={i} {...attrs} />;
    if (tag === 'circle') return <circle key={i} {...attrs} />;
    if (tag === 'line') return <line key={i} {...attrs} />;
    return null;
  });
  return (
    <svg
      width={size}
      height={size}
      viewBox={spec.vb}
      fill={spec.fill ? 'currentColor' : 'none'}
      stroke={spec.fill ? 'none' : 'currentColor'}
      strokeWidth={sw}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="ip-icon"
      aria-hidden="true"
      focusable="false"
    >
      {shapes}
    </svg>
  );
}

/** Lane-local IconButton: ds markup (ip-iconbtn), no Icon render crash. */
export function IconButton(props: {
  icon?: IconName; label?: string; className?: string; children?: ReactNode;
} & Record<string, unknown>): ReactElement {
  const { icon, label, className, children, ...rest } = props;
  return (
    <button
      type="button"
      className={['ip-iconbtn', className].filter(Boolean).join(' ')}
      aria-label={label}
      title={label}
      {...rest}
    >
      {children ?? <IconGlyph name={icon} size={16} />}
    </button>
  );
}

/** Lane-local QuickAction: ds markup (ip-qa + toneClass), no Icon render crash. */
export function QuickAction(props: {
  icon: IconName; title: string; description: string; tone?: Tone; onClick?: () => void;
}): ReactElement {
  return (
    <button type="button" className={['ip-qa', toneClass(props.tone ?? 'accent')].filter(Boolean).join(' ')} onClick={props.onClick}>
      <span className="ip-qa-icon" aria-hidden="true">
        <IconGlyph name={props.icon} size={16} strokeWidth={1.5} />
      </span>
      <span>
        <span className="ip-qa-title">{props.title}</span>
        <span className="ip-qa-desc">{props.description}</span>
      </span>
    </button>
  );
}

/** Lane-local LineChart: ds markup and geometry at a fixed 560px width (no ResizeObserver). */
export function LineChart(props: {
  data: number[]; max?: number; xLabels?: { at: number; text: string }[];
  height?: number; yTicks?: number; label?: string;
}): ReactElement {
  const gid = 'scr' + useId().replace(/[^a-zA-Z0-9]/g, '');
  const data = props.data;
  const height = props.height ?? 130;
  const yTicks = props.yTicks ?? 4;
  const label = props.label ?? 'Throughput';
  const n = data.length;
  const top = n ? Math.max(...data) : 0;
  const mx = props.max || Math.ceil(top / 10) * 10 || 100;
  const labels = props.xLabels || [0, 4, 8, 12, 16, 20].filter((h) => h < n).map((h) => ({ at: h, text: String(h).padStart(2, '0') }));
  const px = 36, pt = 8, pb = 20;
  const w = 560;
  const pw = w - px * 2, ph = height - pt - pb;
  const X = (i: number) => px + (n > 1 ? (pw * i) / (n - 1) : 0);
  const Y = (v: number) => pt + ph * (1 - v / mx);
  let line = '';
  data.forEach((v, i) => { line += (i ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1); });
  const area = 'M' + px + ',' + (pt + ph) + line.replace(/^M/, 'L') + 'L' + (px + pw) + ',' + (pt + ph) + 'Z';
  const grid = [];
  for (let i = 0; i < yTicks; i++) {
    const y = pt + (ph * i) / (yTicks - 1);
    grid.push(
      <g key={i}>
        <line className="ip-chart-grid" x1={px} y1={y} x2={w - px} y2={y} />
        <text className="ip-chart-label" x={px - 6} y={y + 3} textAnchor="end">{Math.round(mx - (mx * i) / (yTicks - 1))}</text>
      </g>
    );
  }
  return (
    <div className="ip-chart">
      <div className="ip-chart-box">
        <svg width={w} height={height} viewBox={'0 0 ' + w + ' ' + height} role="img" aria-label={label + ': ' + n + ' points, latest ' + (n ? data[n - 1] : 0)}>
          <defs>
            <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
              <stop className="ip-chart-stop" offset="0%" stopOpacity="0.25" />
              <stop className="ip-chart-stop" offset="100%" stopOpacity="0" />
            </linearGradient>
          </defs>
          {grid}
          {labels.map((l) => (
            <text key={l.at} className="ip-chart-label" x={X(l.at)} y={height - 4} textAnchor="middle">{l.text}</text>
          ))}
          <path d={area} fill={'url(#' + gid + ')'} />
          <path className="ip-chart-line" d={line} />
          {n ? <circle className="ip-chart-end" cx={X(n - 1)} cy={Y(data[n - 1])} r="4" /> : null}
        </svg>
      </div>
    </div>
  );
}