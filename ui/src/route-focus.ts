// Row 575 (S1): keyboard users bypass the menu and land on the new screen's heading. The router is the URL hash, so the skip
// link cannot be a plain #anchor - it is handled here. Pure over a minimal element shape so node tests need no DOM.
type Focusable = { focus(options?: { preventScroll?: boolean }): void };
type MainLike = Focusable & { querySelector(selector: string): Focusable | null };

/** After a routed address change: the screen's h1 (PageHeader gives it tabIndex -1), else the main landmark. */
export function focusMainHeading(main: MainLike | null): boolean {
  if (!main) return false;
  const heading = main.querySelector('h1');
  (heading ?? main).focus({ preventScroll: true });
  return true;
}

/** Skip link click: cancel the anchor jump (it would change the route) and move focus to main. */
export function skipToMain(event: { preventDefault(): void }, main: Focusable | null): void {
  event.preventDefault();
  main?.focus({ preventScroll: false });
}
