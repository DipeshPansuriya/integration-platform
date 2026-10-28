import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Global search field with the Ctrl K / ⌘K shortcut, which focuses it. */
export function CommandSearch({ placeholder = 'Search flows, systems, contracts...', shortcut, label = 'Search', defaultValue, onSubmit, onChange, className }) {
  const ref = React.useRef(null);
  const [mac, setMac] = React.useState(false);
  React.useEffect(() => {
    setMac(/Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || ''));
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        if (ref.current) ref.current.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <form role="search" className={cx('ip-cmd', className)} onSubmit={(e) => { e.preventDefault(); if (onSubmit) onSubmit(ref.current.value); }}>
      <Icon name="search" size={14} strokeWidth={1.5} />
      <input ref={ref} type="text" placeholder={placeholder} aria-label={label} defaultValue={defaultValue} onChange={onChange ? (e) => onChange(e.target.value) : undefined} />
      <kbd className="ip-kbd">{shortcut || (mac ? '⌘K' : 'Ctrl K')}</kbd>
    </form>
  );
}
