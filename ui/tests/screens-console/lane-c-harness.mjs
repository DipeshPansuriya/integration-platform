// Lane C harness - a copy of tests/ui/hook-harness.mjs (which this lane must NOT modify) with a
// superset react stub: the DS components lane C renders additionally use forwardRef (Input),
// useLayoutEffect (MappingCanvas), memo/useMemo, and `import * as React` users Field.jsx needs
// isValidElement + cloneElement for. Everything else (esbuild bundling, hook runtime, mount
// contract, childElements/findAll) is the stock harness re-used verbatim.

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
export { childElements, findAll } from '../ui/hook-harness.mjs';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const FRAGMENT = Symbol.for('hook-harness.fragment');

// DS Drawer's focus-trap effect touches document; give it an inert host in tests
// (keydown listeners are inert, focus restore is guarded on prev being non-null).
if (typeof globalThis.document !== 'object') {
  globalThis.document = { activeElement: null, addEventListener() {}, removeEventListener() {} };
}

const FAKE_DOM_EL = () => ({
  querySelectorAll: () => [],
  querySelector: () => null,
  focus() {},
  style: {}
});

const STUBS = {
  react: 'export const useState = (i) => globalThis.__hookHarness.useState(i);\n'
    + 'export const useEffect = (f, d) => globalThis.__hookHarness.useEffect(f, d);\n'
    + 'export const useLayoutEffect = (f, d) => globalThis.__hookHarness.useEffect(f, d);\n'
    + 'export const useCallback = (f, d) => globalThis.__hookHarness.useCallback(f, d);\n'
    + 'export const useMemo = (f, d) => f();\n'
    + 'export const useRef = (i) => globalThis.__hookHarness.useRef(i);\n'
    + 'export const useId = () => ":r0:";\n'
    + 'export const createContext = (d) => ({ Provider: Symbol("Provider"), _d: d });\n'
    + 'export const useContext = (c) => c._d;\n'
    + 'export const Fragment = Symbol.for("hook-harness.fragment");\n'
    + 'export const forwardRef = (fn) => { const w = (props) => fn(props, null);'
    + ' Object.defineProperty(w, "name", { value: fn.name || "ForwardRef" }); return w; };\n'
    + 'export const memo = (fn) => { const w = (props) => fn(props);'
    + ' Object.defineProperty(w, "name", { value: fn.name || "Memo" }); return w; };\n'
    + 'export const isValidElement = (x) => !!x && !Array.isArray(x)'
    + ' && typeof x === "object" && "type" in x && "props" in x;\n'
    + 'export const cloneElement = (el, props, ...kids) => ({ ...el,'
    + ' props: kids.length ? { ...el.props, ...props, children: kids.length === 1 ? kids[0] : kids } : { ...el.props, ...props } });\n'
    + 'export const createElement = (type, props, ...kids) =>'
    + ' ({ type, props: { ...props, children: kids.length === 1 ? kids[0] : kids } });\n',
  'react/jsx-runtime': 'const h = (type, props) => globalThis.__hookHarness.element(type, props);\n'
    + 'export const jsx = h; export const jsxs = h; export const Fragment = Symbol.for("hook-harness.fragment");\n'
};

/** Compiles src/<page> with react and the given relative API specifiers stubbed; returns the module. */
async function load(page, apiSpecifiers) {
  const api = new RegExp(`^(${apiSpecifiers.map((s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')).join('|')})$`);
  const result = await build({
    entryPoints: [join(SRC, page)],
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'neutral',
    jsx: 'automatic',
    logLevel: 'silent',
    plugins: [
      {
        name: 'hook-harness-stubs',
        setup(b) {
          b.onResolve({ filter: /^react(\/jsx-runtime)?$/ }, (a) => ({ path: a.path, namespace: 'stub' }));
          b.onResolve({ filter: api }, (a) => ({ path: a.path, namespace: 'api' }));
          b.onLoad({ filter: /\.css$/ }, () => ({ contents: '', loader: 'js' })); // pages import their own stylesheet; Vite handles it, the harness ignores it
          b.onLoad({ filter: /.*/, namespace: 'stub' }, (a) => ({ contents: STUBS[a.path], loader: 'js' }));
          b.onLoad({ filter: /.*/, namespace: 'api' }, () => ({
            contents: 'export default null;\n' + [...new Set(['messageTimeline', 'messageFailures', 'listFlows', 'replayDelivery', 'retryDelivery', 'queueSummary', 'deliveryReport', ...Object.keys(globalThis.__hookHarness.api)])]
              .map((n) => `export const ${n} = (...a) => globalThis.__hookHarness.api.${n}(...a);`).join('\n'),
            loader: 'js'
          }));
        }
      }
    ]
  });
  const code = result.outputFiles[0].text;
  return import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
}

/**
 * Mounts `exportName` from src/<page> with `props`; `api` holds the stubbed calls (lane C screens
 * are presentational: usually none). Returns { tree, setProps, components, unmount, settle } as the
 * stock harness does.
 */
export async function mount(page, exportName, props, api, apiSpecifiers) {
  const states = [];
  const effects = [];
  let stateCursor = 0;
  let effectCursor = 0;
  let pending = [];
  let dirty = false;
  let components = [];
  globalThis.__hookHarness = {
    api: api ?? {},
    useState(initial) {
      const i = stateCursor++;
      if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial;
      return [
        states[i],
        (value) => {
          const next = typeof value === 'function' ? value(states[i]) : value;
          if (!Object.is(next, states[i])) {
            states[i] = next;
            dirty = true;
          }
        }
      ];
    },
    useEffect(effect, deps) {
      const i = effectCursor++;
      const previous = effects[i];
      if (previous && deps && deps.every((d, k) => Object.is(d, previous.deps[k]))) return;
      effects[i] = { deps, cleanup: previous?.cleanup };
      pending.push(() => {
        effects[i].cleanup?.();
        const cleanup = effect();
        effects[i].cleanup = typeof cleanup === 'function' ? cleanup : undefined;
      });
    },
    useRef(initial) {
      const [cell] = this.useState(() => ({ current: initial }));
      return cell;
    },
    useCallback(fn) {
      const [cell] = this.useState(() => ({ fn }));
      return cell.fn;
    },
    element(type, elementProps) {
      if (type === FRAGMENT) return elementProps.children;
      if (typeof type === 'function') {
        components.push({ type, props: elementProps });
        return type(elementProps);
      }
      const host = { type, props: elementProps };
      // DS components attach DOM-element refs (Drawer's focus trap); give the
      // ref a minimal fake node so its effects stay safe under the harness.
      if (elementProps && typeof elementProps.ref === 'object' && elementProps.ref !== null && 'current' in elementProps.ref) {
        elementProps.ref.current = FAKE_DOM_EL();
      }
      return host;
    }
  };
  const Screen = (await load(page, apiSpecifiers ?? []))[exportName];
  let current = null;
  const render = () => {
    for (let pass = 0; pass < 25; pass++) {
      stateCursor = 0;
      effectCursor = 0;
      dirty = false;
      components = [];
      current = Screen(props);
      const run = pending;
      pending = [];
      run.forEach((f) => f());
      if (!dirty) return;
    }
    throw new Error('hook-harness: the screen never settled');
  };
  render();
  return {
    tree: () => current,
    setProps(next) {
      props = next;
      render();
    },
    components: (name) => components.filter((c) => c.type.name === name).map((c) => c.props),
    unmount() {
      effects.forEach((e) => e?.cleanup?.());
    },
    async settle() {
      for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r));
      render();
    }
  };
}

/** The concatenated direct text (strings + numbers) inside a rendered element tree. Accepts either
 * a host element ({ props: { children } }) or the props object components() returns ({ children }). */
export function textOf(node) {
  const out = [];
  const walk = (c) => {
    if (typeof c === 'string' || typeof c === 'number') out.push(String(c));
    else if (Array.isArray(c)) c.forEach(walk);
    else if (c && typeof c === 'object' && 'props' in c) walk(c.props?.children);
    else if (c && typeof c === 'object' && 'children' in c) walk(c.children);
  };
  walk(node?.props?.children ?? node?.children);
  return out.join(' ');
}

/** Fires a mouse-lookalike click event with the preventDefault/stopPropagation stubs. Accepts either
 * a host element or the props object components() returns. */
export function click(el) {
  const p = el && typeof el === 'object' && el.props ? el.props : el;
  return p.onClick({ preventDefault() {}, stopPropagation() {}, target: el });
}