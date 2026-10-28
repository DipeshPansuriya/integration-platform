import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';
import { StatusBadge } from '../feedback/StatusBadge.jsx';

/** One integration flow in the Flows grid: mono name, status, description, meta facts, progress bar. */
export function FlowCard({ name, status = 'Active', description, meta = [], progress = 0, onOpen }) {
  const tone = status === 'Error' ? 'err' : status === 'Draft' ? 'warn' : null;
  return (
    <article className={cx('ip-card ip-flow', onOpen && 'is-interactive')}>
      <div className="ip-flow-top">
        {onOpen
          ? <button type="button" className="ip-card-link ip-flow-name" onClick={onOpen}>{name}</button>
          : <h3 className="ip-flow-name">{name}</h3>}
        <StatusBadge status={status} />
      </div>
      {description && <p className="ip-flow-desc">{description}</p>}
      {meta.length > 0 && (
        <div className="ip-flow-meta">
          {meta.map((m, i) => <span key={i} className={toneClass(m.tone)}>{m.label}: <strong>{m.value}</strong></span>)}
        </div>
      )}
      <div className={cx('ip-flow-bar', toneClass(tone))} aria-hidden="true">
        <span style={{ width: Math.max(0, Math.min(100, progress)) + '%' }} />
      </div>
    </article>
  );
}
