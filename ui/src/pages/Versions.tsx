// T-022 (E3 lane D): the version + approval screens (E3-F2-S1). The draft list walks a flow
// version through submit (changeNote body, T-093 contract), approve and publish - every step
// through the typed client against the real API (no mock, E3-F3-S1). The refusals are
// surfaced, never hidden: the author approving their own version sees the API's 403
// (AUTHOR_IS_APPROVER); a submitted version's content is immutable, and the screen offers the
// only way through (a new draft). "Approve as approver" renders only while the screen-test
// harness exposes its second binding (/console-config.json) - the backend authorizes for real.
// Row 310 lane B (T88, 10-screens §4): a version table + a status-contextual lifecycle panel; the
// silent auto-create of a flow is gone - zero flows shows an empty state pointing to the editor.

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import {
  approveFlow,
  listFlows,
  publishFlow,
  readFlow,
  saveFlowDraft,
  submitFlow,
  type FlowDetail,
  type FlowSummary
} from '../api/flows';
import { hasHarnessLogin, loadHarnessConfig } from '../api/harness-config';
import { statusBadge } from './dashboard-counts';
import { harnessApproveOffered, isImmutable, lifecycleActions, lifecycleSteps, NEW_FLOW_HASH } from './flow-lifecycle';
import './flows.css';

function bundleOf(detail: FlowDetail) {
  // The snapshot the server took at submit is the live rows read back (FlowAdminService.saveDraft);
  // the same four config fields are what the publish materializes over them.
  return JSON.stringify({
    sourceSystem: detail.sourceSystem,
    destinations: detail.destinations,
    businessKeyRule: detail.businessKeyRule,
    ordering: detail.ordering
  });
}

function detailBody(detail: FlowDetail) {
  return {
    sourceSystem: detail.sourceSystem,
    destinations: detail.destinations,
    businessKeyRule: detail.businessKeyRule,
    ordering: detail.ordering
  };
}

type Load = 'loading' | 'ready' | 'error';

export function Versions() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [load, setLoad] = useState<Load>('loading');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selectedRef = useRef<string | null>(null); // handlers act on click time, not render time
  const statusRef = useRef<string>('DRAFT'); // what a click sees, even mid-chain
  const queueRef = useRef<Promise<void>>(Promise.resolve());
  const [status, setStatus] = useState<string>('DRAFT');
  const [changeNote, setChangeNote] = useState('');
  const [refusal, setRefusal] = useState<string | null>(null);
  const [refusalText, setRefusalText] = useState<string | null>(null); // the API's own words for a refused publish
  const [snapshotAtSubmit, setSnapshotAtSubmit] = useState<string | null>(null);
  const [published, setPublished] = useState(false);
  const [bundleEqualsSnapshot, setBundleEqualsSnapshot] = useState<boolean | null>(null);
  const [harnessApprover, setHarnessApprover] = useState(false);

  const setVersionStatus = (next: string) => {
    statusRef.current = next;
    setStatus(next);
  };
  /** The clicked row becomes THE selected version: handlers act on it, and every surface that shows
   * or compares per version (status, submit snapshot, published bundle) re-syncs to it. */
  const selectFlow = (flow: FlowSummary) => {
    setSelectedId(flow.id);
    selectedRef.current = flow.id;
    setVersionStatus(flow.status);
    setSnapshotAtSubmit(null); // the snapshot belongs to the row that submitted it
    setPublished(false);
    setBundleEqualsSnapshot(null);
    setRefusal(null); // a refusal answered for the previous row
  };
  /** Clicks are fire-and-forget for the specs; actions run in click order on real answers. */
  const enqueue = (action: () => Promise<void>) => {
    queueRef.current = queueRef.current.then(() => action().catch((e) => console.error('versions action failed', e)));
  };

  useEffect(() => {
    void loadHarnessConfig().then(() => setHarnessApprover(hasHarnessLogin('approver')));
    void listFlows().then((list) => {
      if (!list.ok) {
        setLoad('error');
        return;
      }
      setFlows(list.data);
      setLoad('ready');
      // Select (never create): the first draft row, else the first row.
      const first = list.data.find((row) => row.status === 'DRAFT') ?? list.data[0];
      if (first && selectedRef.current === null) selectFlow(first);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const detailOf = async (id: string): Promise<FlowDetail | null> => {
    const read = await readFlow(id);
    return read.ok ? read.data : null;
  };

  const onSubmit = async () => {
    const id = selectedRef.current;
    if (!id) return;
    setRefusal(null);
    const detail = await detailOf(id);
    if (!detail) return;
    setSnapshotAtSubmit(bundleOf(detail));
    const note = changeNote.trim() || 'console submit (no change note given)';
    let result = await submitFlow(id, note);
    if (!result.ok && result.status === 409) {
      // No draft to submit: the immutable-version design says the only way is a NEW draft (T-019).
      await saveFlowDraft(id, detailBody(detail));
      result = await submitFlow(id, note);
    }
    if (result.ok) setVersionStatus('SUBMITTED');
  };

  const onApprove = async (asLogin?: string) => {
    const id = selectedRef.current;
    if (!id) return;
    setRefusal(null);
    const result = await approveFlow(id, asLogin);
    if (result.ok) {
      setVersionStatus('APPROVED');
      return;
    }
    if (result.refusal === 'AUTHOR_IS_APPROVER') setRefusal('author-cannot-approve');
    else if (result.status === 409) setRefusal('not-pending-approval');
    else setRefusal(`approve-refused-${result.status}`);
  };

  const onPublish = async () => {
    const id = selectedRef.current;
    if (!id) return;
    setRefusal(null);
    const result = await publishFlow(id);
    if (!result.ok) {
      // T83: a server-refused publish (e.g. the T-037 sample-run gate's 409) is shown, never swallowed.
      setRefusalText(result.error ?? `the API answered ${result.status}`);
      setRefusal('publish');
      return;
    }
    setPublished(true);
    setVersionStatus('PUBLISHED');
    const detail = await detailOf(id);
    setBundleEqualsSnapshot(detail !== null && snapshotAtSubmit !== null && bundleOf(detail) === snapshotAtSubmit);
  };

  const saveCurrentDraft = async (): Promise<boolean> => {
    const id = selectedRef.current;
    if (!id) return false;
    const detail = await detailOf(id);
    if (!detail) return false;
    const result = await saveFlowDraft(id, detailBody(detail));
    if (result.ok) setVersionStatus('DRAFT');
    return result.ok;
  };

  const onSaveDraft = async () => {
    if (isImmutable(statusRef.current)) {
      // E3-F2-S1 @negative: a click that lands mid-chain (after submit answered) is refused here.
      setRefusal('submitted-version-immutable');
      return;
    }
    await saveCurrentDraft();
  };

  const onNewDraft = async () => {
    if (await saveCurrentDraft()) {
      setRefusal(null);
      setPublished(false);
      setBundleEqualsSnapshot(null);
    }
  };

  const onRowKey = (event: KeyboardEvent, flow: FlowSummary) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      selectFlow(flow);
    }
  };

  const actions = lifecycleActions(status);
  const selected = flows.find((flow) => flow.id === selectedId) ?? null;

  return (
    <div data-page="versions" className="versions-page">
      <h2>Versions and approval</h2>
      {load === 'loading' && <p className="flows-muted" aria-live="polite">Loading flows...</p>}
      {load === 'error' && (
        <p role="alert" className="flows-alert flows-alert-danger" data-load-error="flows">
          The flow list could not be loaded. Reload the page to try again.
        </p>
      )}
      {load === 'ready' && flows.length === 0 && (
        <div className="flows-empty" data-empty="flows">
          <p>No flows yet - Define a flow first.</p>
          <a className="btn btn-primary" href={NEW_FLOW_HASH}>Define a flow</a>
        </div>
      )}
      {flows.length > 0 && (
        <div className="versions-zones">
          <div className="flows-grid">
            <table className="table" aria-label="Flow versions">
              <thead>
                <tr>
                  <th scope="col">Flow</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {flows.map((flow) => {
                  const isSelected = flow.id === selectedId;
                  const shown = isSelected ? status : flow.status;
                  const badge = statusBadge(shown);
                  return (
                    // B14: aria-selected is valid only in grid/listbox roles; aria-current names the selected
                    // row on a plain table, the hidden text is what AT reads, bold + bar are the non-colour cue.
                    <tr
                      key={flow.id}
                      data-flow-row={flow.id}
                      aria-current={isSelected ? 'true' : undefined}
                      tabIndex={0}
                      onClick={() => selectFlow(flow)}
                      onKeyDown={(event) => onRowKey(event, flow)}
                    >
                      <td>
                        {flow.name || flow.code}
                        {isSelected && <span className="flows-sr-only"> (selected)</span>}
                      </td>
                      <td>
                        <span className={`badge badge-${badge.tone}`} data-version-status={shown}>
                          {badge.word}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {selected && (
            <section className="versions-panel" aria-label="Lifecycle of the selected version">
              <h3>{selected.name || selected.code}</h3>
              <ol className="versions-stepper">
                {lifecycleSteps(status).map((step) => (
                  <li key={step.status} data-step={step.state} aria-current={step.state === 'current' ? 'step' : undefined}>
                    {step.word}
                  </li>
                ))}
              </ol>

              {actions.includes('submit-for-approval') && (
                <label className="flows-field">
                  Change note
                  <input data-field="change-note" value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
                </label>
              )}

              <div className="versions-actions">
                {actions.includes('save-draft') && (
                  <button type="button" className="btn" data-action="save-draft" onClick={() => enqueue(onSaveDraft)}>
                    Save draft
                  </button>
                )}
                {actions.includes('submit-for-approval') && (
                  <button type="button" className="btn btn-primary" data-action="submit-for-approval" onClick={() => enqueue(onSubmit)}>
                    Submit for approval
                  </button>
                )}
                {actions.includes('approve') && (
                  <button type="button" className="btn btn-primary" data-action="approve" onClick={() => enqueue(() => onApprove())}>
                    Approve
                  </button>
                )}
                {actions.includes('publish') && (
                  <button type="button" className="btn btn-primary" data-action="publish" disabled={status !== 'APPROVED'} onClick={() => enqueue(onPublish)}>
                    Publish
                  </button>
                )}
                {actions.includes('new-draft') && (
                  <button type="button" className="btn" data-action="new-draft" onClick={() => enqueue(onNewDraft)}>
                    New draft
                  </button>
                )}
              </div>

              {isImmutable(status) && refusal !== 'submitted-version-immutable' && (
                <p className="flows-alert flows-alert-info" data-notice="submitted-version-immutable">
                  A submitted version is immutable. A new draft is the only way to change it.
                </p>
              )}
              {status === 'SUBMITTED' && <p className="flows-muted">Waiting for approval.</p>}
              {status === 'PUBLISHED' && <p className="flows-muted">This version is published and read-only.</p>}

              {refusal === 'author-cannot-approve' && (
                <div role="alert" className="flows-alert flows-alert-danger" data-refusal="author-cannot-approve">
                  <p>Refused: the author of a version cannot approve it themselves. Ask the listed approver to approve this version.</p>
                  <details>
                    <summary>Details</summary>
                    <code>AUTHOR_IS_APPROVER</code>
                  </details>
                </div>
              )}
              {refusal === 'submitted-version-immutable' && (
                <p role="alert" className="flows-alert flows-alert-danger" data-refusal="submitted-version-immutable">
                  The submitted version cannot be edited: a new draft version is the only way.
                </p>
              )}
              {refusal === 'not-pending-approval' && (
                <p role="alert" className="flows-alert flows-alert-danger" data-refusal="not-pending-approval">
                  No version is pending approval.
                </p>
              )}
              {refusal === 'publish' && (
                <p role="alert" className="flows-alert flows-alert-danger" data-refusal="publish">
                  Publish refused: {refusalText}
                </p>
              )}
              {refusal !== null && !['author-cannot-approve', 'submitted-version-immutable', 'not-pending-approval', 'publish'].includes(refusal) && (
                <p role="alert" className="flows-alert flows-alert-danger" data-refusal={refusal}>
                  The API refused this action ({refusal}).
                </p>
              )}
              {published && <p className="flows-alert flows-alert-success" data-published="true">Published.</p>}
              {bundleEqualsSnapshot !== null && (
                <p data-bundle-equals-snapshot={String(bundleEqualsSnapshot)}>
                  Published bundle equals the snapshot taken at submit: {String(bundleEqualsSnapshot)}.
                </p>
              )}

              {harnessApproveOffered(status, harnessApprover) && (
                <div className="versions-harness" aria-label="Test harness">
                  <span className="flows-muted">Test harness</span>
                  <button type="button" className="btn versions-ghost" data-action="approve-as-approver" onClick={() => enqueue(() => onApprove('approver'))}>
                    Approve as the harness approver
                  </button>
                </div>
              )}
            </section>
          )}
        </div>
      )}
    </div>
  );
}
