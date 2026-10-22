// Row 323 T139 (E15-F1-S1): the browser journey's gate. Mirrors platform-e2e Release1Pending.ON_MAIN
// (flip BOTH when an item lands; tests/release1/unit/pending-gate.test.mjs proves each flag against the tree and the two maps agree). A spec whose items are not all on main is test.fixme - reported as
// not run, never as passed; the Java SuiteVerdict counts a not-run scenario red.

export const ON_MAIN = Object.freeze({
  T95: true, T104: true, T105: true, T106: true, T107: true, T109: true, T110: true, T113: true,
  T96: true, T98: true, T99: true, T111: true, T112: true, T115: true, T120: true,
  T121: true, T143: true, T144: true, T157: true
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
