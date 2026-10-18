// Row 323 T139 (E15-F1-S1): the browser journey's gate. Mirrors platform-e2e Release1Pending.ON_MAIN
// (flip BOTH when an item lands). A spec whose items are not all on main is test.fixme - reported as
// not run, never as passed; the Java SuiteVerdict counts a not-run scenario red.

export const ON_MAIN = Object.freeze({
  T95: true, T104: true, T105: true, T106: true, T107: true, T109: true, T110: true, T113: true,
  T96: false, T98: false, T99: false, T111: false, T112: false, T115: false, T120: false,
  T121: false, T143: false, T144: false, T157: false
});

/** null when every item is on main; otherwise the reason string for test.fixme. */
export function waitsOn(...items) {
  if (items.length === 0) throw new Error('waitsOn: name at least one item');
  const unknown = items.filter((item) => !(item in ON_MAIN));
  if (unknown.length) throw new Error(`waitsOn: unknown item(s) ${unknown.join(', ')}`);
  const missing = items.filter((item) => !ON_MAIN[item]);
  return missing.length
    ? `waits on ${missing.join(', ')} (not on main) - flip ON_MAIN.${missing[0]} here and in Release1Pending.java`
    : null;
}
