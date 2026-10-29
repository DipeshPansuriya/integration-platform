import { findAll, mount as baseMount } from '../ui/hook-harness.mjs';

export { findAll };

/** Lane A compatibility shim: tests read `.props` on component nodes while the
 * stock harness returns the prop bag directly. Return proxy objects exposing both
 * the legacy `.props` shape and direct prop access, without touching the shared
 * harness. */
export async function mount(...args) {
  const view = await baseMount(...args);
  const baseComponents = view.components.bind(view);
  view.components = (name) => baseComponents(name).map((props) => {
    const node = { props };
    return Object.assign(node, props || {});
  });
  return view;
}
