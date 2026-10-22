/**
 * E7 wave 1 lane D1 (T-034 + T-035, row 278): the mapping designer for one interface-target pair -
 * `#/mappings/<routeId>`. The route's saved mapping (T-190 API) is the graph; the two schema trees
 * come from exactly the bound source/target contract versions; drawing (add/change/delete) lands
 * back on the same pair only through the typed client. The canvas module is lazy-loaded here (the
 * page file itself stays synchronous - the shell in App.tsx is not edited), so the React Flow
 * bundle is its own chunk (the jdm-editor 6.5 MB lesson).
 */

import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import {
  addLink as addLinkToGraph, changeLink as changeLinkOnGraph, deleteLink as deleteLinkFromGraph,
  emptyGraph, parseSavedMapping, schemaPaths, targetRefusal, toSaveRequest
} from '../mapping/graph';
import { applyExpressionEdit as applyRawEdit } from '../mapping/expression/raw';
import { emitJsonata } from '../mapping/emit/emit';
import { MappingRoutes } from '../mapping/MappingRoutes';
import { MappingSamplePanel } from '../mapping/MappingSamplePanel';
import { saveRefusal } from '../mapping/saveRefusal';
import '../mapping/mapping.css';
import type { FieldSchema, MappingGraph, SavedMappingAnswer } from '../mapping/graph';
import type { CanvasSelected } from '../mapping/MappingCanvas';
import { canSaveMapping, classifyMappingLoad, designerKey, saveThrownText } from './mapping-load';
import { failureText } from './flow-save';

const MappingCanvas = lazy(() => import('../mapping/MappingCanvas'));
// T-039 (E7 wave 2 lane D2): the CodeMirror raw-expression editor, its own lazy chunk like the canvas.
const RawExpressionEditor = lazy(() => import('../mapping/expression/RawExpressionEditor'));

type VersionOption = {
  id: string;
  system: string;
  role: string;
  status: string;
  versionLabel: string;
};

type ContractBody = {
  id?: string;
  system?: string;
  role?: string;
  versions?: Array<{ id?: string; versionLabel?: string; status?: string }>;
};

type MappingErrorBody = { message?: string | null; code?: string | null } | null;

const refusalOf = (error: unknown): string => {
  const body = error as MappingErrorBody;
  return body?.message ?? JSON.stringify(body ?? 'the mapping could not be saved');
};

/** The drawn links ARE the mapping (emit.ts, ADR-004 rule 3): a link edit re-emits the expression the
 * save stores, so "Run sample" runs what was drawn. A refused emission (one target drawn twice) keeps
 * the previous expression and names the refusal. */
const withEmitted = (next: MappingGraph): { graph: MappingGraph; refusal: string | null } => {
  const emitted = emitJsonata(next.links);
  return emitted.refusal
    ? { graph: next, refusal: emitted.refusal }
    : { graph: { ...next, expression: emitted.expression }, refusal: null };
};

const loadVersion = async (versionId: string) => {
  const { data, response } = await api.GET('/api/v1/admin/contracts/versions/{versionId}', {
    params: { path: { versionId } }
  });
  // A5: a contract version that did not load must not read as "no fields" (Save would then skip its guard).
  if (!response?.ok) throw new Error(`contract version ${response?.status ?? 0}`);
  return data as { jsonSchema?: FieldSchema | null } | null;
};

/** A6: one designer per route - the key remounts it, so nothing of the previous route's state survives. */
export function MappingDesigner({ routeId }: { routeId: string | null }) {
  return <MappingDesignerRoute key={designerKey(routeId)} routeId={routeId} />;
}

function MappingDesignerRoute({ routeId }: { routeId: string | null }) {
  const [loaded, setLoaded] = useState(false);
  // A5: a failed load (anything but 404) is an error with Retry; Save stays off.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [missing, setMissing] = useState(false);
  const [graph, setGraph] = useState<MappingGraph>(emptyGraph());
  const [targetSchema, setTargetSchema] = useState<FieldSchema | null>(null);
  const [versions, setVersions] = useState<VersionOption[]>([]);
  const [selected, setSelected] = useState<CanvasSelected>({ source: null, target: null });
  const [refusal, setRefusal] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  // T92: the sample runs the SAVED mapping - its id, and whether the drawing has unsaved edits.
  const [mappingId, setMappingId] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (!routeId) {
      setLoaded(true); // the #/mappings landing: no pair is open yet
      return;
    }
    setLoaded(false);
    setLoadError(null);
    setDirty(false);
    Promise.all([
      api.GET('/api/v1/admin/routes/{routeId}/mapping', { params: { path: { routeId } } }),
      api.GET('/api/v1/admin/contracts')
    ]).then(async ([mapping, contracts]) => {
      if (cancelled) return;
      const outcome = classifyMappingLoad(mapping);
      if (outcome.kind === 'missing') {
        setMissing(true); // another tenant's route (ADR-011 Decision 4) or an unknown id
        setLoaded(true);
        return;
      }
      if (outcome.kind === 'failed' || !contracts.response?.ok) {
        setLoadError(outcome.kind === 'failed' ? outcome.text : failureText('open the mapping', contracts.response?.status ?? 0));
        setLoaded(true);
        return;
      }
      const savedAnswer = outcome.saved;
      setMappingId(savedAnswer.mappingId ?? null);
      setGraph(parseSavedMapping(savedAnswer));
      // Re-render exactly as left: the two trees come from exactly the bound contract versions.
      if (savedAnswer.sourceContractVersionId || savedAnswer.targetContractVersionId) {
        const [sourceDetail, targetDetail] = await Promise.all([
          savedAnswer.sourceContractVersionId ? loadVersion(savedAnswer.sourceContractVersionId) : null,
          savedAnswer.targetContractVersionId ? loadVersion(savedAnswer.targetContractVersionId) : null
        ]);
        if (cancelled) return;
        setTargetSchema(targetDetail?.jsonSchema ?? null);
        setGraph((current) => ({
          ...current,
          sourceFields: sourceDetail?.jsonSchema ? schemaPaths(sourceDetail.jsonSchema) : [],
          targetFields: targetDetail?.jsonSchema ? schemaPaths(targetDetail.jsonSchema) : []
        }));
      }
      const options: VersionOption[] = [];
      for (const contract of (contracts.data as { contracts?: ContractBody[] } | undefined)?.contracts ?? []) {
        for (const version of contract.versions ?? []) {
          if (contract.system && version.id && contract.role) {
            options.push({
              id: version.id,
              system: contract.system,
              role: contract.role,
              status: version.status ?? '',
              versionLabel: version.versionLabel ?? ''
            });
          }
        }
      }
      setVersions(options);
      setLoaded(true);
    }).catch(() => {
      if (cancelled) return;
      setLoadError(failureText('open the mapping', 0));
      setLoaded(true);
    });
    return () => {
      cancelled = true;
    };
  }, [routeId, attempt]);

  /** The schema trees exactly of the bound contract versions: fetching one side never drops the
   * other side's tree, and already drawn links stay (the save guard refuses stale ones). */
  const bindSide = async (side: 'sourceContractVersionId' | 'targetContractVersionId', versionId: string) => {
    setSaved(false);
    setDirty(true);
    const schema = versionId ? (await loadVersion(versionId))?.jsonSchema ?? null : null;
    setTargetSchema((current) => (side === 'targetContractVersionId' ? schema : current));
    setGraph((current) => ({
      ...current,
      binding: { ...current.binding, [side]: versionId || null },
      ...(side === 'sourceContractVersionId'
        ? { sourceFields: schema ? schemaPaths(schema) : [] }
        : { targetFields: schema ? schemaPaths(schema) : [] })
    }));
  };

  // Selecting a node always selects exactly that side - drawing runs source -> target only.
  const selectNode = useCallback((side: 'source' | 'target', path: string) => {
    setSelected((current) => ({
      source: side === 'source' ? path : current.source,
      target: side === 'target' ? path : current.target
    }));
  }, []);

  const drawLink = () => {
    if (!selected.source || !selected.target) return;
    setSaved(false);
    setDirty(true);
    const { graph: next, refusal: refused } = addLinkToGraph(graph, selected.source, selected.target);
    if (refused) {
      setRefusal(refused); // named field (the target contract does not have it)
      return;
    }
    const emitted = withEmitted(next);
    setGraph(emitted.graph);
    setRefusal(emitted.refusal);
  };

  const changeTarget = (id: string, target: string) => {
    setSaved(false);
    setDirty(true);
    const { graph: next, refusal: refused } = changeLinkOnGraph(graph, id, { target });
    if (refused) {
      setGraph(next);
      setRefusal(refused);
      return;
    }
    const emitted = withEmitted(next);
    setGraph(emitted.graph);
    setRefusal(emitted.refusal);
  };

  const removeLink = (id: string) => {
    setSaved(false);
    setDirty(true);
    const emitted = withEmitted(deleteLinkFromGraph(graph, id));
    setGraph(emitted.graph);
    setRefusal(emitted.refusal);
  };

  // T-039: a raw-expression edit lands back through applyExpressionEdit - a $round edit (ADR-006
  // rule 8: money rounding never via $round) is refused here and shown with the rule named.
  const editExpression = (text: string) => {
    // The editor echoes every programmatic sync (load, save, re-emit) through onEdit with the same
    // text - only a real edit marks the drawing unsaved.
    if (text === graph.expression) return;
    setSaved(false);
    setDirty(true);
    const { graph: next, refusal: refused } = applyRawEdit(graph, text);
    setGraph(next);
    setRefusal(refused);
  };

  async function save() {
    if (!routeId) return;
    setSaved(false);
    const { sourceContractVersionId, targetContractVersionId } = graph.binding;
    if (!sourceContractVersionId !== !targetContractVersionId) {
      setRefusal('a mapping binds one source and one target contract version together - select both');
      return;
    }
    if (targetSchema) {
      const refused = targetRefusal(graph, targetSchema);
      if (refused) {
        setRefusal(refused);
        return;
      }
    }
    // T57: while the emitter refuses the drawn links the expression is the previous one - never PUT it.
    const drawnRefusal = saveRefusal(graph);
    if (drawnRefusal) {
      setRefusal(drawnRefusal);
      return;
    }
    setRefusal(null);
    try {
      const { data, error } = await api.PUT('/api/v1/admin/routes/{routeId}/mapping', {
        params: { path: { routeId } },
        body: toSaveRequest(graph) as never
      });
      if (!error && data) {
        setGraph({
          ...parseSavedMapping(data as SavedMappingAnswer),
          sourceFields: graph.sourceFields,
          targetFields: graph.targetFields
        });
        setMappingId((data as SavedMappingAnswer).mappingId ?? mappingId);
        setDirty(false);
        setSaved(true); // stored for this pair only; re-renders exactly as it was left
      } else {
        setRefusal(refusalOf(error));
      }
    } catch {
      setRefusal(saveThrownText()); // no answer at all: nothing was saved, and the user is told
    }
  }

  const sourceOptions = versions.filter((v) => v.role === 'SOURCE_REQUEST');
  const targetOptions = versions.filter((v) => v.role === 'TARGET_REQUEST' && v.status === 'APPROVED');

  const unbound = graph.sourceFields.length === 0 && graph.targetFields.length === 0;
  // 10-screens s8: the same empty state for an unknown route and a route with nothing drawn yet - the
  // route's existence is never leaked (a 404 is also another tenant's answer, ADR-011 Decision 4).
  const emptyState = (
    <p className="mapping-empty">
      This route has no mapping yet. Bind the two contract versions and draw the first link.
    </p>
  );

  return (
    <div data-page="mapping-designer" data-mapping-loaded={loaded ? 'true' : undefined} className="mapping-page">
      <div className="mapping-head">
        <h2>Mapping designer</h2>
        {routeId && <a href="#/mappings" data-action="all-mappings">All mappings</a>}
      </div>
      {routeId ? (
        loadError !== null ? (
          <div role="alert" className="mapping-card mapping-alert mapping-alert-danger" data-load-error="mapping">
            <p>{loadError}</p>
            <button type="button" className="btn" data-action="retry-load" onClick={() => setAttempt((n) => n + 1)}>
              Retry
            </button>
          </div>
        ) : missing ? (
          <section className="mapping-card mapping-canvas-card" aria-label="Mapping canvas" data-mapping-missing="true">
            {emptyState}
          </section>
        ) : (
          <>
            <div className="mapping-card mapping-bind">
              <label>
                Source contract version{' '}
                <select
                  data-field="source-version"
                  value={graph.binding.sourceContractVersionId ?? ''}
                  onChange={(e) => void bindSide('sourceContractVersionId', e.target.value)}
                >
                  <option value="">not bound</option>
                  {sourceOptions.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.system} v{v.versionLabel} ({v.status.toLowerCase()})
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Target contract version{' '}
                <select
                  data-field="target-version"
                  value={graph.binding.targetContractVersionId ?? ''}
                  onChange={(e) => void bindSide('targetContractVersionId', e.target.value)}
                >
                  <option value="">not bound</option>
                  {targetOptions.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.system} v{v.versionLabel}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <section className="mapping-card mapping-canvas-card" aria-label="Mapping canvas">
              <div className="mapping-canvas-legend" aria-hidden="true">
                <span>Source fields</span>
                <span>Target fields</span>
              </div>
              {unbound && graph.links.length === 0 ? (
                emptyState
              ) : (
                <Suspense fallback={<p className="mapping-empty" data-canvas-loading="true">Loading the mapping canvas...</p>}>
                  <MappingCanvas graph={graph} selected={selected} onNodeSelect={selectNode} />
                </Suspense>
              )}
              <div className="mapping-actions">
                <p
                  className="mapping-selection"
                  data-selection={`source:${selected.source ?? '-'} target:${selected.target ?? '-'}`}
                >
                  Source <span className="mapping-mono">{selected.source ?? 'none'}</span> {'→'} target{' '}
                  <span className="mapping-mono">{selected.target ?? 'none'}</span>
                </p>
                <button
                  type="button"
                  className="btn"
                  data-action="add-link"
                  onClick={drawLink}
                  disabled={!selected.source || !selected.target}
                >
                  Link selected fields
                </button>
                <button type="button" className="btn btn-primary" data-action="save" onClick={() => void save()} disabled={!canSaveMapping({ routeId, loaded, loadError })}>
                  Save
                </button>
                <div className="mapping-actions-note">
                  {refusal && (
                    <p role="alert" className="mapping-alert mapping-alert-danger" data-refusal>
                      {refusal}
                    </p>
                  )}
                  {!refusal && saved && (
                    <p role="status" className="mapping-alert mapping-alert-success" data-saved="true">
                      Mapping saved.
                    </p>
                  )}
                </div>
              </div>
            </section>
            <div className="mapping-grid">
              <table className="table">
                <caption>Drawn links (the drawing, per interface-target pair)</caption>
                <thead>
                  <tr>
                    <th scope="col">Source field</th>
                    <th scope="col">Target field</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {graph.links.map((link) => (
                    <tr key={link.id} data-link-row={`${link.source}->${link.target}`}>
                      <td>
                        <input data-field="link-source" value={link.source} readOnly aria-label={`Source field of link ${link.source}`} />
                      </td>
                      <td>
                        <input
                          data-field="link-target"
                          value={link.target}
                          aria-label={`Target field of link from ${link.source}`}
                          onChange={(e) => changeTarget(link.id, e.target.value)}
                        />
                      </td>
                      <td>
                        <button type="button" className="btn" data-action="delete-link" onClick={() => removeLink(link.id)}>
                          Delete
                        </button>
                      </td>
                    </tr>
                  ))}
                  {graph.links.length === 0 && (
                    <tr>
                      <td colSpan={3} className="mapping-muted">No links drawn yet.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <MappingSamplePanel mappingId={mappingId} dirty={dirty} />
            <div className="mapping-card">
              <p>
                <label>Raw expression (JSONata - typed, ADR-006)</label>
              </p>
              <Suspense fallback={<p className="mapping-muted" data-raw-loading="true">Loading the raw expression editor...</p>}>
                <RawExpressionEditor graph={graph} onEdit={editExpression} />
              </Suspense>
            </div>
          </>
        )
      ) : (
        <>
          <p className="mapping-lede">Each route of an interface has one mapping: a source contract version bound to the target version.</p>
          <MappingRoutes />
        </>
      )}
    </div>
  );
}