// Row 310 lane C (T101): the Drawer's pure behaviour - no React, no DOM - so node --test can hold
// the keyboard contract (Esc closes, Tab stays inside) without a browser. Drawer.tsx applies it.

/** The drawer's one width (10-screens §5: "a right-side drawer (480px)"); drawer.css carries the same number. */
export const DRAWER_WIDTH_PX = 480;

/** Everything a keyboard user can land on inside the panel. */
export const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** What a key does while the drawer is open: Esc closes, Tab is trapped, anything else passes through. */
export function drawerKeyAction(key: string): 'close' | 'trap' | null {
  if (key === 'Escape' || key === 'Esc') return 'close';
  if (key === 'Tab') return 'trap';
  return null;
}

/** The next element index for Tab (or Shift+Tab) inside `count` focusables, wrapping at both ends; -1 when there is none. */
export function nextFocusIndex(count: number, current: number, backwards: boolean): number {
  if (count <= 0) return -1;
  if (current < 0) return backwards ? count - 1 : 0;
  return backwards ? (current - 1 + count) % count : (current + 1) % count;
}

/** A close request (Esc, scrim, Close) on a form with unsaved input asks first instead of discarding it (B29 C3). */
export function closeAction(dirty: boolean): 'close' | 'confirm' {
  return dirty ? 'confirm' : 'close';
}
