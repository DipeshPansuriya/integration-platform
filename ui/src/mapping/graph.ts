/**
 * T-034 (E7 wave 1 lane D1, row 278): the mapping graph contract - ONE module lane D owns and
 * lane B reads (never edits): the drawn canvas state, the saved shape the mapping API (T-190)
 * stores, and the refusals. Pure TypeScript: no React, no @xyflow/react - so `node --test`
 * (tests/mapping/graph.test.mjs, T-034 red-first) runs it and lane B's emitter (T-036) imports it.
 *
 * The three scenarios this contract serves (backlog.md:1824-1844):
 *   - Map Customer A to Target A v1.0: links of one interface-target pair, bound contract versions.
 *   - Add, change and delete elements: the mutators below.
 *   - A link to a field the target contract does not have is refused - and names the field.
 */

/** One drawn link between two contract fields (dotted paths). The id is the canvas's own; it is
 * stripped on save (the backend stores { source, target } entries only - MappingContent). */
export interface MappingLink {
  id: string;
  source: string;
  target: string;
}

/** The mapping binds exactly one source and one target contract version (ADR-004 rule 3). */
export interface MappingBinding {
  sourceContractVersionId: string | null;
  targetContractVersionId: string | null;
}

/** The whole drawn graph: the binding, its links, the compiled expression the backend keeps, and
 * the field paths of the two bound contract versions' schemas (the two trees). */
export interface MappingGraph {
  binding: MappingBinding;
  links: MappingLink[];
  expression: string;
  sourceFields: string[];
  targetFields: string[];
}

/** What GET/PUT /api/v1/admin/routes/{routeId}/mapping answer
 * (MappingAdminController.read/save - ui/src/api/openapi.d.ts at tip 73bf600). */
export interface SavedMappingAnswer {
  routeId?: string;
  mappingId?: string;
  fieldMappings?: Array<{ source?: string; target?: string } | null>;
  compiledExpression?: string | null;
  sourceContractVersionId?: string | null;
  targetContractVersionId?: string | null;
  mappingVersionId?: string | null;
}

/** A JSON Schema (draft 2020-12) of a contract version (ContractVersionBody.jsonSchema). */
export type FieldSchema = {
  type?: string | string[];
  properties?: Record<string, unknown>;
  items?: { properties?: Record<string, unknown> };
};

const REFUSAL = (field: string) => `the target contract does not have field ${field}`;

/** The empty two-sided graph (an unbound route's mapping row). */
export function emptyGraph(): MappingGraph {
  return {
    binding: { sourceContractVersionId: null, targetContractVersionId: null },
    links: [],
    expression: '$',
    sourceFields: [],
    targetFields: []
  };
}

/** The saved mapping as the graph to re-render: exactly as left (E7-F2-S1 "re-renders exactly as
 * left"). The saved entries carry no ids, so ids are re-minted positionally and never leave the UI. */
export function parseSavedMapping(answer: SavedMappingAnswer): MappingGraph {
  const links = (answer.fieldMappings ?? []).map((entry, index) => ({
    id: `link-${index}`,
    source: String(entry?.source ?? ''),
    target: String(entry?.target ?? '')
  }));
  return {
    binding: {
      sourceContractVersionId: answer.sourceContractVersionId ?? null,
      targetContractVersionId: answer.targetContractVersionId ?? null
    },
    links,
    expression: answer.compiledExpression ?? '$',
    sourceFields: [],
    targetFields: []
  };
}

/** The PUT body: the backend's own shape - no canvas ids, the binding, and the mandatory
 * compiled expression (MappingAdminService.save refuses a blank one). */
export function toSaveRequest(graph: MappingGraph): {
  compiledExpression: string;
  sourceContractVersionId: string | null;
  targetContractVersionId: string | null;
  fieldMappings: Array<{ source: string; target: string }>;
} {
  return {
    compiledExpression: graph.expression,
    sourceContractVersionId: graph.binding.sourceContractVersionId,
    targetContractVersionId: graph.binding.targetContractVersionId,
    fieldMappings: graph.links.map(({ source, target }) => ({ source, target }))
  };
}

/** The field paths of one contract version's schema for its tree: top-level names, nested objects
 * and array item properties flattened with dotted paths (a tree of the bound contract version). */
export function schemaPaths(schema: FieldSchema | null | undefined): string[] {
  const fields: string[] = [];
  const walk = (node: FieldSchema | null | undefined, prefix: string) => {
    for (const [name, child] of Object.entries(node?.properties ?? {})) {
      const path = prefix ? `${prefix}.${name}` : name;
      fields.push(path);
      const typed = child as FieldSchema;
      if (typed.type === 'object') walk(typed, path);
      if (typed.type === 'array') walk(typed.items as FieldSchema | undefined, path);
    }
  };
  walk(schema, '');
  return fields;
}

/** The save-time guard for links that arrived from storage (not through the mutators): the first
 * link whose target the bound target contract does not have, named - or null. */
export function targetRefusal(graph: MappingGraph, targetSchema: FieldSchema | null | undefined): string | null {
  const fields = new Set(schemaPaths(targetSchema));
  for (const link of graph.links) {
    if (!fields.has(link.target)) return REFUSAL(link.target);
  }
  return null;
}

export type MutatorResult = { graph: MappingGraph; refusal: string | null };

/** An element is added: a link drawn between a source field and a field the target contract has. */
export function addLink(graph: MappingGraph, source: string, target: string): MutatorResult {
  if (!graph.targetFields.includes(target)) {
    return { graph, refusal: REFUSAL(target) };
  }
  return {
    graph: {
      ...graph,
      links: [...graph.links, { id: `link-${graph.links.length}`, source, target }]
    },
    refusal: null
  };
}

/**
 * L28: "Auto-map matching names" - links every source field whose path the target contract also has
 * (exact path), skipping a pair that is already linked. Never removes or changes a drawn link.
 */
export function autoMatchLinks(graph: MappingGraph): { graph: MappingGraph; added: number } {
  let next = graph;
  let added = 0;
  for (const source of graph.sourceFields) {
    if (!graph.targetFields.includes(source)) continue;
    if (next.links.some((link) => link.source === source && link.target === source)) continue;
    next = addLink(next, source, source).graph;
    added += 1;
  }
  return { graph: next, added };
}

/** A link changed: retarget (or re-source) one drawn link; refused paths are not applied. */
export function changeLink(
  graph: MappingGraph,
  id: string,
  patch: Partial<Pick<MappingLink, 'source' | 'target'>>
): MutatorResult {
  const existing = graph.links.find((link) => link.id === id);
  if (!existing) return { graph, refusal: null };
  const next = { ...existing, ...patch };
  if (patch.target !== undefined && !graph.targetFields.includes(next.target)) {
    return { graph, refusal: REFUSAL(next.target) };
  }
  return {
    graph: { ...graph, links: graph.links.map((link) => (link.id === id ? next : link)) },
    refusal: null
  };
}

/** An element deleted: the drawn link is gone; the rest keep their ids. */
export function deleteLink(graph: MappingGraph, id: string): MappingGraph {
  return { ...graph, links: graph.links.filter((link) => link.id !== id) };
}