/**
 * T-039 (E7 wave 2 lane D2, row 278): the CodeMirror 6 raw-expression editor of the mapping
 * designer (`codemirror` 6.0.2, MIT - licence-checked by npm run licence:check). ADR-006 picked
 * the Dashjoin JSONata 0.9.10 language, so the raw surface a configurator types is JSONata; this
 * component mounts it and reports every edit through the pure contract
 * (ui/src/mapping/expression/raw.ts - renderExpression / applyExpressionEdit held by
 * tests/mapping/expression.test.mjs). The page lazy-loads this module like MappingCanvas, so no
 * other screen pays for the editor bundle (the jdm-editor 6.5 MB lesson - memory m-4cd53c).
 */

import { useEffect, useRef } from 'react';
import { EditorState } from '@codemirror/state';
import { EditorView, minimalSetup } from 'codemirror';
import type { MappingGraph } from '../graph';
import { renderExpression } from './raw';

/**
 * The typed surface of the graph's expression. Every edit is handed to `onEdit` as the full
 * document text (the page runs it through applyExpressionEdit, so a $round edit is refused
 * there); when the expression changes from outside (a new saved mapping loaded), the editor
 * re-renders exactly what storage keeps - except while it already holds that same text, so user
 * typing is never clobbered mid-edit.
 */
export default function RawExpressionEditor({
  graph,
  onEdit
}: {
  graph: MappingGraph;
  onEdit: (text: string) => void;
}) {
  const host = useRef<HTMLDivElement | null>(null);
  const view = useRef<EditorView | null>(null);
  const latest = useRef(onEdit);
  latest.current = onEdit;
  const expression = renderExpression(graph);

  useEffect(() => {
    const editor = new EditorView({
      state: EditorState.create({
        doc: expression,
        extensions: [
          minimalSetup,
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({ 'aria-label': 'Raw expression' }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) latest.current(update.state.doc.toString());
          })
        ]
      }),
      parent: host.current ?? undefined
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // The editor is mounted once; later expression changes flow through the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const editor = view.current;
    if (!editor || expression === editor.state.doc.toString()) return;
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: expression } });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expression]);

  return (
    <div
      data-field="raw-expression"
      className="raw-expression"
      style={{ border: '1px solid #8a939e', borderRadius: 4 }}
      ref={host}
    />
  );
}