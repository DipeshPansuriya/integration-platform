// Row 327 T112 (lane D - vijay-developer, row 328 V1/V3): renders ONE console screen (.tsx) under plain
// `node --test`, with no DOM and no test library added. esbuild (already installed as Vite's own bundler)
// compiles the screen; `react` is swapped for a tiny hook runtime (useState + useEffect + a first-wins useCallback - what the
// screens use) and the screen's API module for the stubs a test passes. The result is a plain element
// tree: { type, props } objects a test can search by data-* attributes and whose handlers it can call.
// ponytail: a hand-rolled hook runtime instead of jsdom + Testing Library (not installed); it models
// only state, effects with deps and cleanups, and re-render until stable - enough for load-state rules.

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const FRAGMENT = Symbol.for('hook-harness.fragment');

const STUBS = {
  react: 'export const useState = (i) => globalThis.__hookHarness.useState(i);\n'
    + 'export const useEffect = (f, d) => globalThis.__hookHarness.useEffect(f, d);\n'
    + 'export const useCallback = (f, d) => globalThis.__hookHarness.useCallback(f, d);\n'
    + 'export const useRef = (i) => globalThis.__hookHarness.useRef(i);\n'
    + 'export const useId = () => ":r0:";\n'
    + 'export const createContext = (d) => ({ Provider: Symbol("Provider"), _d: d });\n'
    + 'export const useContext = (c) => c._d;\n'
    + 'export const Fragment = Symbol.for("hook-harness.fragment");\n',
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
 * Mounts `exportName` from src/<page> with `props`; `api` holds the stubbed calls. Returns
 * { tree(), settle() } - settle() lets pending promises resolve and re-renders until nothing changes.
 */
export async function mount(page, exportName, props, api, apiSpecifiers) {
  const states = [];
  const effects = [];
  let stateCursor = 0;
  let effectCursor = 0;
  let pending = [];
  let dirty = false;
  let components = []; // [{ type, props }] of every function component rendered in the last pass - lets a test reach a child's props (e.g. onDone)
  globalThis.__hookHarness = {
    api,
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
    // ponytail: the first function wins (the screens that use useCallback pass only setters, so no stale-closure risk).
    useCallback(fn) {
      const [cell] = this.useState(() => ({ fn }));
      return cell.fn;
    },
    element(type, elementProps) {
      if (type === FRAGMENT) return elementProps.children;
      if (typeof type === 'function') {
        components.push({ type, props: elementProps });
        return type(elementProps); // hook-free child components only
      }
      return { type, props: elementProps };
    }
  };
  const Screen = (await load(page, apiSpecifiers))[exportName];
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
    /** Re-renders with new props (a route change on a mounted screen). */
    setProps(next) {
      props = next;
      render();
    },
    components: (name) => components.filter((c) => c.type.name === name).map((c) => c.props),
    /** Runs every effect cleanup, as React does when the screen leaves. */
    unmount() {
      effects.forEach((e) => e?.cleanup?.());
    },
    async settle() {
      for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r));
      render();
    }
  };
}

/** Flat list of the element children (arrays and fragments flattened, false/null/strings dropped). */
export function childElements(node) {
  const out = [];
  const walk = (c) => {
    if (Array.isArray(c)) c.forEach(walk);
    else if (c && typeof c === 'object' && 'type' in c) out.push(c);
  };
  walk(node?.props?.children);
  return out;
}

/** Every element in the tree matching `test`, depth first. */
export function findAll(root, test) {
  const out = [];
  const walk = (n) => {
    if (Array.isArray(n)) return n.forEach(walk);
    if (!n || typeof n !== 'object' || !('type' in n)) return;
    if (test(n)) out.push(n);
    walk(n.props?.children);
  };
  walk(root);
  return out;
}
