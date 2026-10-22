// Row 310 lane C (T101): the ONE right-side drawer (480px) the Systems and Contracts screens share
// (10-screens §5/§6, 02 drawer recipe). A modal dialog: role="dialog" + aria-modal, labelled by its
// title; closed means not rendered; on open focus moves to the first field (or the panel), Tab stays
// inside, Esc / the Close button / the scrim close it, and focus goes back to whatever opened it.
//
// Props API (lane E may consume it later):
//   open: boolean          - rendered only while true
//   title: string          - the visible heading and the dialog's accessible name
//   onClose: () => void    - Esc, Close button and scrim click all call it; the owner sets open=false
//   children: ReactNode    - the drawer body (a form, a refusal, ...)
//   id?: string            - stamped as data-drawer={id}, a hook for screen tests
//   closeLabel?: string    - the Close button's word (default "Close")
// B29 C3: once a person types in the body, Esc / scrim / Close ask "Discard changes?" inline first;
// submitting a form clears that (the values were sent), so a saved drawer closes without asking.

import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { FOCUSABLE, closeAction, drawerKeyAction, nextFocusIndex } from './drawer-keys';
import './drawer.css';

export type DrawerProps = {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  id?: string;
  closeLabel?: string;
};

export function Drawer({ open, title, onClose, children, id, closeLabel = 'Close' }: DrawerProps) {
  if (!open) return null;
  return (
    <DrawerPanel title={title} onClose={onClose} id={id} closeLabel={closeLabel}>
      {children}
    </DrawerPanel>
  );
}

function DrawerPanel({ title, onClose, children, id, closeLabel }: Omit<DrawerProps, 'open'>) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const [dirty, setDirty] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const requestClose = () => {
    if (closeAction(dirty) === 'confirm') setConfirming(true);
    else onClose();
  };

  // focus in on open, back to the opener on close (unmount)
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const first = panel.current?.querySelector<HTMLElement>(`form ${FOCUSABLE}`) ?? panel.current;
    first?.focus();
    return () => {
      if (opener && opener.isConnected) opener.focus();
    };
  }, []);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const action = drawerKeyAction(event.key);
    if (action === 'close') {
      event.stopPropagation();
      if (confirming) setConfirming(false);
      else requestClose();
    } else if (action === 'trap' && panel.current) {
      const items = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      const next = nextFocusIndex(items.length, items.indexOf(document.activeElement as HTMLElement), event.shiftKey);
      if (next >= 0) {
        event.preventDefault();
        items[next].focus();
      }
    }
  };

  return (
    <div className="drawer-layer">
      <div className="drawer-scrim" aria-hidden="true" onClick={requestClose} />
      <div
        ref={panel}
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-drawer={id}
        onKeyDown={onKeyDown}
      >
        <div className="drawer-head">
          <h2 id={titleId} className="drawer-title">
            {title}
          </h2>
          <button type="button" className="btn" data-action="close-drawer" onClick={requestClose}>
            {closeLabel}
          </button>
        </div>
        {confirming && (
          <div className="drawer-discard" role="alertdialog" aria-labelledby={`${titleId}-discard`}>
            <p id={`${titleId}-discard`}>Discard changes? What you typed here has not been saved.</p>
            <button type="button" className="btn" data-action="keep-editing" autoFocus onClick={() => setConfirming(false)}>
              Keep editing
            </button>
            <button type="button" className="btn btn-primary" data-action="discard-changes" onClick={onClose}>
              Discard changes
            </button>
          </div>
        )}
        <div className="drawer-body" onInput={() => setDirty(true)} onSubmit={() => setDirty(false)}>
          {children}
        </div>
      </div>
    </div>
  );
}
