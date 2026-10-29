import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Bordered mono grid for decision tables and field mappings. Column kind: 'cond' (accent), 'out' (ok) or 'plain'. */
export function DecisionTable({ columns, rows, caption }) {
  const k = (c) => (c.kind === 'cond' ? 'is-cond' : c.kind === 'out' ? 'is-out' : null);
  return (
    <div className="ip-table-wrap">
      <table className="ip-dt">
        {caption && <caption className="ip-sr">{caption}</caption>}
        <thead><tr>{columns.map((c) => <th key={c.key} scope="col" className={cx(c.kind === 'out' && 'is-out')}>{c.header}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{columns.map((c) => <td key={c.key} className={k(c)}>{r[c.key]}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
