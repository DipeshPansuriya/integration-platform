import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

const ROLE = { source: 'ip-tone-accent', validate: 'ip-tone-warn', transform: 'ip-pipe--transform', target: 'ip-tone-ok' };

/** Flow stages as outlined chips with arrows: source → validate → transform → target. */
export function Pipeline({ stages, label = 'Pipeline' }) {
  return (
    <ol className="ip-pipe" aria-label={label}>
      {stages.map((s, i) => (
        <li key={i} className="ip-pipe-step">
          <span className={cx('ip-pipe-chip', ROLE[s.role] || 'ip-tone-accent')}>{s.label}</span>
          {i < stages.length - 1 && <Icon name="arrow" size={[20, 12]} className="ip-pipe-arrow" />}
        </li>
      ))}
    </ol>
  );
}
