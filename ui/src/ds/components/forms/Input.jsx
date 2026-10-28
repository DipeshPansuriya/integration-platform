import * as React from 'react';
import { cx } from '../_shared/util.js';

/** Text input. `code` sets mono + uppercase for identifiers (flow names, system codes). */
export const Input = React.forwardRef(function Input({ code = false, className, ...rest }, ref) {
  return <input ref={ref} className={cx('ip-input', code && 'ip-input--code', className)} {...rest} />;
});
