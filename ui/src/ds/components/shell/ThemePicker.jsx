import * as React from 'react';
import { cx } from '../_shared/util.js';
import { IconButton } from '../actions/IconButton.jsx';
import { Icon } from '../brand/Icon.jsx';

export const ACCENTS = [
  ['indigo', 'Indigo'],
  ['emerald', 'Emerald'],
  ['rose', 'Rose'],
  ['amber', 'Amber'],
  ['cyan', 'Cyan'],
  ['violet', 'Violet'],
];

/** Reads, applies and persists the theme: data-accent and data-theme on the target (default <html>), localStorage keys ip-t / ip-m. */
export function useConsoleTheme({ accent: a0 = 'indigo', mode: m0, target, persist = true } = {}) {
  const read = (k, d) => {
    if (!persist) return d;
    try { return localStorage.getItem(k) || d; } catch (e) { return d; }
  };
  const systemMode = () => {
    try { return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'; } catch (e) { return 'light'; }
  };
  const [accent, setAccent] = React.useState(() => read('ip-t', a0));
  const [mode, setMode] = React.useState(() => read('ip-m', m0 || systemMode()));
  React.useEffect(() => {
    const el = (target && target.current) || document.documentElement;
    el.setAttribute('data-accent', accent);
    el.setAttribute('data-theme', mode);
    if (persist) {
      try { localStorage.setItem('ip-t', accent); localStorage.setItem('ip-m', mode); } catch (e) { /* storage blocked */ }
    }
  }, [accent, mode]);
  return { accent, mode, setAccent, setMode, toggleMode: () => setMode((m) => (m === 'dark' ? 'light' : 'dark')) };
}

/** Topbar theme control: a palette button opening a popover with six accent swatches and a Light / Dark choice. */
export function ThemePicker({ accent = 'indigo', mode = 'light', onAccentChange, onModeChange, defaultOpen = false, inline = false }) {
  const [open, setOpen] = React.useState(defaultOpen);
  const wrap = React.useRef(null);
  React.useEffect(() => {
    if (!open || inline) return undefined;
    const onDoc = (e) => { if (wrap.current && !wrap.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey); };
  }, [open, inline]);
  const pop = (
    <div className={cx('ip-cpop', inline && 'is-inline')} role="dialog" aria-label="Theme">
      <p className="ip-cpop-label">Theme color</p>
      <div className="ip-cpop-grid" role="group" aria-label="Theme color">
        {ACCENTS.map(([id, name]) => (
          <button key={id} type="button" aria-pressed={accent === id} aria-label={name} title={name} className="ip-swatch" style={{ background: 'var(--' + id + ')' }} onClick={() => onAccentChange && onAccentChange(id)} />
        ))}
      </div>
      <p className="ip-cpop-label">Mode</p>
      <div className="ip-mode-row" role="group" aria-label="Mode">
        {[['light', 'Light'], ['dark', 'Dark']].map(([id, name]) => (
          <button key={id} type="button" aria-pressed={mode === id} className="ip-mode-btn" onClick={() => onModeChange && onModeChange(id)}>{name}</button>
        ))}
      </div>
    </div>
  );
  if (inline) return pop;
  return (
    <span ref={wrap} className="ip-cpop-wrap">
      <IconButton label="Theme color" aria-expanded={open} aria-haspopup="dialog" onClick={() => setOpen((o) => !o)}>
        <Icon name="palette" size={16} />
      </IconButton>
      {open && pop}
    </span>
  );
}
ThemePicker.accents = ACCENTS;
ThemePicker.useConsoleTheme = useConsoleTheme;
