import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Console table: raised uppercase header, hairline rows, accent hover. Columns: { key, header, align, mono, numeric, render, width }. */
export function DataTable({ columns, rows, rowKey, onRowClick, caption, className }) {
  return (
    <div className={cx('ip-table-wrap', className)}>
      <table className="ip-table">
        {caption && <caption className="ip-sr">{caption}</caption>}
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={cx(c.align === 'right' && 'is-right')} style={c.width ? { width: c.width } : undefined}>{c.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr
              key={rowKey ? r[rowKey] : i}
              className={cx(onRowClick && 'is-clickable')}
              tabIndex={onRowClick ? 0 : undefined}
              onClick={onRowClick ? () => onRowClick(r) : undefined}
              onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(r); } } : undefined}
            >
              {columns.map((c) => {
                const v = c.render ? c.render(r) : r[c.key];
                return (
                  <td key={c.key} className={cx(c.align === 'right' && 'is-right', c.numeric && 'is-num')}>
                    {c.mono ? <span className="ip-id">{v}</span> : v}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
