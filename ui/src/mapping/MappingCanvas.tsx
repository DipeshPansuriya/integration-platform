/**
 * T-034 + T-035 (E7 wave 1 lane D1, row 278): the React Flow canvas of the mapping designer
 * (`@xyflow/react` 12.12.0, MIT - ADR-004). The two schema trees of the bound contract versions
 * are drawn nodes (source column left, target column right); the drawn links are edges between
 * them. The page (ui/src/pages/MappingDesigner.tsx) lazy-loads this module, so no other screen
 * pays for the canvas bundle (the jdm-editor 6.5 MB lesson - memory m-4cd53c), and owns the graph
 * state; this component only renders it and reports clicks (ui/src/mapping/graph.ts holds the
 * contract both lane D and lane B read).
 */

import { useMemo } from 'react';
import type { Node as FlowNode, Edge, NodeProps } from '@xyflow/react';
import { Background, Handle, Position, ReactFlow } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { MappingGraph } from './graph';

export type CanvasSelected = { source: string | null; target: string | null };

type FieldNodeData = {
  path: string;
  side: 'source' | 'target';
  selected: boolean;
};
type FieldNodeFlow = FlowNode<FieldNodeData, 'field'>;

/** One field of a bound contract version's schema tree, as a canvas node. Clicking selects it for
 * the next drawn link (the accessible list of links lives in the page). */
function FieldNode({ data }: NodeProps<FieldNodeFlow>) {
  return (
    <div
      data-tree={data.side}
      style={{
        border: data.selected ? '2px solid #0b5cab' : '1px solid #8a939e',
        borderRadius: 4,
        background: data.side === 'source' ? '#f4f7fb' : '#f8fbf4',
        padding: '4px 8px'
      }}
    >
      {/* The left handle accepts links, the right (source) handle draws them: an edge always runs
          source tree -> target tree, so a link can never be drawn backwards. */}
      <Handle type="target" position={Position.Left} id="l" isConnectable={false} />
      <button type="button" data-node={data.path} aria-pressed={data.selected}>
        {data.path}
      </button>
      <Handle type="source" position={Position.Right} id="r" isConnectable={false} />
    </div>
  );
}

const NODE_TYPES = { field: FieldNode };
const ROW = 46;
const TARGET_COLUMN_X = 340;

export default function MappingCanvas({
  graph,
  selected,
  onNodeSelect
}: {
  graph: MappingGraph;
  selected: CanvasSelected;
  onNodeSelect: (side: 'source' | 'target', path: string) => void;
}) {
  const nodes = useMemo<FieldNodeFlow[]>(() => {
    const column = (side: 'source' | 'target', fields: string[]): FieldNodeFlow[] =>
      fields.map((path, index) => ({
        id: `${side}:${path}`,
        type: 'field' as const,
        draggable: false, // the layout is the tree; edges follow the columns
        position: {
          x: side === 'source' ? 0 : TARGET_COLUMN_X,
          y: index * ROW
        },
        data: { path, side, selected: selected[side] === path }
      }));
    return [...column('source', graph.sourceFields), ...column('target', graph.targetFields)];
  }, [graph.sourceFields, graph.targetFields, selected]);

  const edges = useMemo<Edge[]>(
    () =>
      graph.links.map((link) => ({
        id: link.id,
        source: `src:${link.source}`,
        target: `tgt:${link.target}`,
        sourceHandle: 'r',
        targetHandle: 'l',
        label: `${link.source} → ${link.target}`,
        deletable: false // a link is deleted through the page's link row, which re-renders the truth
      })),
    [graph.links]
  );

  return (
    <div data-canvas="mapping" style={{ height: 480, minWidth: 560 }}>
      <ReactFlow
        nodes={nodes}
        nodeTypes={NODE_TYPES}
        edges={edges}
        fitView
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_, node) => onNodeSelect(node.data.side, node.data.path)}
      >
        <Background />
      </ReactFlow>
    </div>
  );
}