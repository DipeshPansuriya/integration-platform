// BS-14 (lane 4): the pure Tab-walk judge. The spec presses Tab and records which control holds
// focus after each press (a stable id, or null when focus left the document); this decides trap,
// coverage and order (WCAG 2.1.1 keyboard, 2.1.2 no keyboard trap, 2.4.3 focus order).
// Unit: tests/a11y/unit/tab-walk.test.mjs.

/**
 * `expected` = the page's tabbable controls in DOM order; `visited` = focus holder after each Tab.
 * The walk ends at the first null (focus left the page) or at the first repeat. A repeat before every
 * expected control was seen is a trap: focus cycles among a subset and never reaches the rest.
 */
export function analyseTabWalk({ expected, visited }) {
  const seen = new Set();
  const firstVisits = [];
  let trap = false;

  for (const id of visited) {
    if (id === null) break;
    if (seen.has(id)) {
      trap = expected.some((e) => !seen.has(e));
      break;
    }
    seen.add(id);
    firstVisits.push(id);
  }

  const known = new Set(expected);
  const missing = expected.filter((id) => !seen.has(id));
  const extra = firstVisits.filter((id) => !known.has(id));

  const rank = new Map(expected.map((id, i) => [id, i]));
  const outOfOrder = [];
  let highest = -1;
  for (const id of firstVisits) {
    if (!rank.has(id)) continue;
    const r = rank.get(id);
    if (r < highest) outOfOrder.push(id);
    else highest = r;
  }

  return { missing, extra, outOfOrder, trap, completed: missing.length === 0 };
}
