import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';
import { KeyValue } from './KeyValue.jsx';

/** Card for a system, rule or mapping: tinted icon chip, name, "type · detail" line, key-value facts. */
export function EntityCard({ icon, tone = 'accent', name, typeLine, rows = [], onOpen, cardProps, children }) {
  return (
    <article {...cardProps} className={cx('ip-card ip-entity', onOpen && 'is-interactive')}>
      <div className="ip-entity-top">
        <span className={cx('ip-entity-icon', toneClass(tone))} aria-hidden="true"><Icon name={icon} size={18} /></span>
        <div>
          {onOpen
            ? <button type="button" className="ip-card-link ip-entity-name" onClick={onOpen}>{name}</button>
            : <h3 className="ip-entity-name">{name}</h3>}
          {typeLine && <div className="ip-entity-type">{typeLine}</div>}
        </div>
      </div>
      {rows.map((r, i) => <KeyValue key={i} label={r.label} value={r.value} tone={r.tone} mono={r.mono} />)}
      {children}
    </article>
  );
}
