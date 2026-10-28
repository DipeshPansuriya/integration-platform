import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Right-hand 520px panel for create and detail views. Closes on ×, overlay click and Esc; traps focus while open. */
export function Drawer({ open = false, title, onClose, footer, children }) {
  const panel = React.useRef(null);
  const titleId = React.useId();
  React.useEffect(() => {
    if (!open) return undefined;
    const prev = document.activeElement;
    const el = panel.current;
    const list = () => Array.from(el.querySelectorAll(FOCUSABLE));
    const t = setTimeout(() => {
      const first = el.querySelector('.ip-drawer-body ' + FOCUSABLE.split(',').join(',.ip-drawer-body '));
      (first || el).focus({ preventScroll: true });
    }, 40);
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); if (onClose) onClose(); return; }
      if (e.key !== 'Tab') return;
      const f = list();
      if (!f.length) return;
      const a = f[0];
      const z = f[f.length - 1];
      if (e.shiftKey && document.activeElement === a) { e.preventDefault(); z.focus(); }
      else if (!e.shiftKey && document.activeElement === z) { e.preventDefault(); a.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      clearTimeout(t);
      document.removeEventListener('keydown', onKey);
      if (prev && prev.focus) prev.focus({ preventScroll: true });
    };
  }, [open]);
  return (
    <div className={cx('ip-drawer-overlay', open && 'is-open')} aria-hidden={!open} onMouseDown={(e) => { if (e.target === e.currentTarget && onClose) onClose(); }}>
      <div ref={panel} className="ip-drawer" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <div className="ip-drawer-head">
          <h2 className="ip-drawer-title" id={titleId}>{title}</h2>
          <button type="button" className="ip-drawer-close" aria-label="Close" onClick={onClose}>
            <Icon name="close" size={14} strokeWidth={1.8} />
          </button>
        </div>
        <div className="ip-drawer-body">{children}</div>
        {footer && <div className="ip-drawer-foot">{footer}</div>}
      </div>
    </div>
  );
}

function DrawerSection({ title, children }) {
  return (
    <section className="ip-drawer-section">
      {title && <h3 className="ip-drawer-label">{title}</h3>}
      {children}
    </section>
  );
}
Drawer.Section = DrawerSection;
