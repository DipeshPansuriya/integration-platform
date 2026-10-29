import * as React from 'react';
import { IconButton } from '../actions/IconButton.jsx';
import { Icon } from '../brand/Icon.jsx';

/** Topbar light/dark switch: a moon in light mode, a sun in dark mode. */
export function ModeToggle({ mode = 'light', onToggle, className }) {
  const dark = mode === 'dark';
  return (
    <IconButton
      className={className}
      label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      onClick={() => onToggle && onToggle(dark ? 'light' : 'dark')}
    >
      <Icon name={dark ? 'sun' : 'moon'} size={16} />
    </IconButton>
  );
}
