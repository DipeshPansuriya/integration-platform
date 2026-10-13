/**
 * T-036 (E7 wave 2 lane B3, row 278): the graph -> JSONata emitter. It derives the compiled
 * expression the backend stores from the drawn links alone - the drawn graph IS the mapping
 * (ADR-004 rule 3), so what was drawn is what runs, textually equal to the hand-written
 * reference mappings (ui/tests/mapping/emitter.test.mjs, 10 of 10 equal strings).
 *
 * Emission convention (Release 1):
 *   - a JSONata object template: `{ 'target': source-navigation, ... }`
 *   - a dotted TARGET path nests object templates and merges links sharing a root
 *     (`ship_to.street` + `ship_to.zip` -> `{'ship_to': {'street': ..., 'zip': ...}}`)
 *   - a dotted SOURCE path is plain JSONata navigation (`address.street` stays as is)
 *   - keys are always single-quoted (any field name survives it - dashes, digits-leading,
 *     names that collide with JSONata's function names)
 *   - the drawn order is the emitted order; a re-draw in another order is another mapping
 *   - nothing drawn emits `$`, the empty graph's identity expression (graph.ts)
 *   - two links into one target field (or one target drawn both leaf and parent) are refused -
 *     one target field is produced by one link (the "two targets never share one mapping"
 *     rule, stated from the emitter side); a refused emission carries `''`, the blank the
 *     backend save refuses (MappingAdminService)
 *
 * Reads graph.ts (T-034, lane D) - never edits it; a change goes through arjun-fullstack.
 */

import type { MappingLink } from '../graph';

export interface EmitResult {
  /** The JSONata expression; `''` when the links are refused. */
  expression: string;
  refusal: string | null;
}

/** One node of the target-side prefix tree: children keyed by the next path segment. */
interface TargetNode {
  children: Map<string, TargetNode>;
  /** The source path of the leaf at this exact target path, or null when only a parent. */
  source: string | null;
}

const newNode = (): TargetNode => ({ children: new Map(), source: null });

/** An object-template key: always single-quoted (escaped), any field name survives it. */
function key(name: string): string {
  return `'${name.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

export function emitJsonata(links: readonly Pick<MappingLink, 'source' | 'target'>[]): EmitResult {
  if (links.length === 0) {
    return { expression: '$', refusal: null };
  }

  // Refusals first: an exact duplicate target, or one target drawn both leaf and parent.
  const drawnTargets = new Set<string>();
  for (const link of links) {
    if (drawnTargets.has(link.target)) {
      return { expression: '', refusal: `the target field ${link.target} is drawn twice` };
    }
    drawnTargets.add(link.target);
  }

  // The target forest: roots in first-drawn order, each link nested by its dotted path.
  const forest = new Map<string, TargetNode>();
  for (const { source, target } of links) {
    const segments = target.split('.');
    let level = forest;
    let node: TargetNode | undefined;
    let path = '';
    for (let i = 0; i < segments.length; i++) {
      path = path ? `${path}.${segments[i]}` : segments[i];
      node = level.get(segments[i]);
      if (!node) node = newNode();
      if (i === segments.length - 1 && node.source !== null) {
        return {
          expression: '',
          refusal: `the target field ${path} is drawn twice (both as a field and as a parent)`
        };
      }
      if (i === segments.length - 1) node.source = source;
      level.set(segments[i], node);
      level = node.children;
    }
    if (node!.source === null) {
      return {
        expression: '',
        refusal: `the target field ${target} is drawn twice (both as a field and as a parent)`
      };
    }
  }

  const render = (node: TargetNode): string => {
    if (node.children.size === 0) return node.source!; // plain navigation: the source path
    const inner = [...node.children.entries()]
      .map(([name, child]) => `${key(name)}: ${render(child)}`)
      .join(', ');
    return `{${inner}}`;
  };
  // the root names are the template's keys: depth-1 targets are leaves carrying them too
  const expression = `{${[...forest.entries()]
    .map(([name, node]) => `${key(name)}: ${render(node)}`)
    .join(', ')}}`;
  return { expression, refusal: null };
}