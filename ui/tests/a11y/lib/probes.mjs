// BS-14 (lane 4): the in-page probes. Each export is a SELF-CONTAINED function handed to
// page.evaluate (it is serialised, so it may not close over anything in this file). They only GATHER raw
// facts - computed-style strings, ids, labels. Every verdict is made in Node by the pure helpers
// (color.mjs, focus.mjs, tab-walk.mjs, aria-tree.mjs), which have node --test units.

/**
 * Mark every control Tab can reach with data-a11y-id (DOM order) and return them. Mirrors Chromium's
 * sequential focus rules: visible, not disabled/inert, tabindex >= 0, one stop per radio group.
 */
export function markTabbables() {
  const SELECTOR = [
    'a[href]', 'area[href]', 'button', 'input:not([type="hidden"])', 'select', 'textarea', 'summary',
    '[tabindex]', '[contenteditable=""]', '[contenteditable="true"]',
  ].join(',');
  const visible = (el) => el.checkVisibility({ checkOpacity: false, checkVisibilityCSS: true }) && el.getClientRects().length > 0;
  const radioSeen = new Set();
  const out = [];
  let n = 0;
  for (const el of document.querySelectorAll(SELECTOR)) {
    if (el.matches(':disabled') || el.closest('[inert]') || !visible(el)) continue;
    const tabindex = el.hasAttribute('tabindex') ? parseInt(el.getAttribute('tabindex'), 10) : null;
    if (tabindex !== null && tabindex < 0) continue;
    if (el.tagName === 'SUMMARY' && el.parentElement?.tagName !== 'DETAILS') continue;
    if (el.tagName === 'INPUT' && el.type === 'radio' && el.name) {
      const group = [...document.querySelectorAll(`input[type="radio"][name="${CSS.escape(el.name)}"]`)];
      const stop = group.find((r) => r.checked) ?? group[0];
      if (el !== stop || radioSeen.has(el.name)) continue;
      radioSeen.add(el.name);
    }
    n += 1;
    const id = `t${n}`;
    el.setAttribute('data-a11y-id', id);
    out.push({
      id,
      tag: el.tagName.toLowerCase(),
      label: (el.getAttribute('aria-label') || el.getAttribute('data-action') || el.getAttribute('data-nav') || el.getAttribute('data-field') || el.textContent || el.getAttribute('name') || '').trim().slice(0, 40),
      positiveTabindex: tabindex !== null && tabindex > 0,
    });
  }
  return out;
}

/**
 * Put the sequential-focus starting point at the very top of the document, so the first Tab lands on
 * the first control (pages that focus a heading on load would otherwise start mid-page).
 */
export function resetFocusStart() {
  const sentinel = document.createElement('span');
  sentinel.tabIndex = -1;
  document.body.prepend(sentinel);
  sentinel.focus();
  sentinel.remove();
  window.scrollTo(0, 0);
}

/** What holds focus now, with the computed focus-indicator facts and the layers behind it. */
export function focusSnapshot() {
  const el = document.activeElement;
  if (!el || el === document.body || el === document.documentElement) return null;
  const cs = getComputedStyle(el);
  const layers = [];
  for (let e = el; e; e = e.parentElement) {
    const s = getComputedStyle(e);
    layers.push({ color: s.backgroundColor, image: s.backgroundImage });
  }
  return {
    id: el.getAttribute('data-a11y-id') ?? `unmarked:${el.tagName.toLowerCase()}:${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30)}`,
    label: (el.getAttribute('aria-label') || el.getAttribute('data-action') || el.textContent || '').trim().slice(0, 40),
    tag: el.tagName.toLowerCase(),
    focusVisible: el.matches(':focus-visible'),
    style: { outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor, boxShadow: cs.boxShadow },
    layers,
    inDialog: Boolean(el.closest('[role="dialog"],[role="alertdialog"]')),
  };
}

/**
 * Every visible text-bearing element under `roots` with its colours, size and the background layers
 * behind it. Identical samples are merged (count) so a table of 200 equal cells is one sample.
 */
export function sampleText(roots) {
  const SKIP = 'script,style,noscript,option,optgroup,template,svg,canvas';
  const FIELD = 'input,select,textarea';
  const merged = new Map();
  const describe = (el) => {
    const attr = el.getAttribute('data-action') || el.getAttribute('data-nav') || el.getAttribute('data-field');
    const cls = typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : '';
    return `${el.tagName.toLowerCase()}${attr ? `[${attr}]` : ''}${cls}`;
  };
  for (const root of roots) {
    for (const host of document.querySelectorAll(root)) {
      for (const el of [host, ...host.querySelectorAll('*')]) {
        if (el.matches(SKIP) || el.closest(SKIP)) continue;
        const isField = el.matches(FIELD) && el.type !== 'checkbox' && el.type !== 'radio' && el.type !== 'hidden';
        const ownText = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join(' ').trim();
        const fieldText = isField ? String(el.value ?? '').trim() || (el.tagName === 'SELECT' ? el.selectedOptions[0]?.textContent?.trim() ?? '' : '') : '';
        const text = ownText || fieldText;
        if (!text) continue;
        if (el.closest('[aria-hidden="true"],[hidden],[inert]') || el.matches(':disabled') || el.closest(':disabled')) continue;
        if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) continue;
        const rect = el.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) continue;
        const cs = getComputedStyle(el);
        const layers = [];
        let opacity = 1;
        for (let e = el; e; e = e.parentElement) {
          const s = getComputedStyle(e);
          layers.push({ color: s.backgroundColor, image: s.backgroundImage });
          opacity *= parseFloat(s.opacity);
        }
        const key = JSON.stringify([cs.color, cs.fontSize, cs.fontWeight, opacity, layers]);
        const hit = merged.get(key);
        if (hit) {
          hit.count += 1;
        } else {
          merged.set(key, { selector: describe(el), text: text.slice(0, 40), color: cs.color, fontSize: cs.fontSize, fontWeight: cs.fontWeight, opacity, layers, count: 1 });
        }
      }
    }
  }
  return [...merged.values()];
}

/** Theme actually applied by the pre-paint boot. */
export function appliedTheme() {
  return document.documentElement.dataset.theme ?? null;
}
