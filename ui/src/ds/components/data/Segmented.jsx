import * as React from 'react';
import { cx, useControlled } from '../_shared/util.js';

/** Compact period switch (1H · 24H · 7D · 30D) for panel headers. */
export function Segmented({ options = ['1H', '24H', '7D', '30D'], value, defaultValue = '24H', onChange, label = 'Period', className }) {
  const [current, set] = useControlled(value, defaultValue, onChange);
  return (
    <div className={cx('ip-seg', className)} role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o} type="button" aria-pressed={current === o} onClick={() => set(o)}>{o}</button>
      ))}
    </div>
  );
}
