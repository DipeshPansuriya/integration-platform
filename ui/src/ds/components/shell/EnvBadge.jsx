import * as React from 'react';
import { cx, toneClass } from '../_shared/util.js';

/** Environment pill with a pulsing dot. Production = ok; suggested Staging = warn, Dev = info. */
export function EnvBadge({ env = 'Production', tone = 'ok', className }) {
  return (
    <span className={cx('ip-env', toneClass(tone), className)}>
      <span className="ip-env-dot" aria-hidden="true" />
      {env}
    </span>
  );
}
