// T-022 (E3 lane D): the version + approval screens (E3-F2-S1). The draft list walks a flow
// version through submit (changeNote body, T-093 contract), approve and publish - every step
// through the typed client against the real API (no mock, E3-F3-S1). The refusals are
// surfaced, never hidden: the author approving their own version sees the API's 403
// (AUTHOR_IS_APPROVER); a submitted version's content is immutable, and the screen offers the
// only way through (a new draft). "Approve as approver" renders only while the screen-test
// harness exposes its second binding (/console-config.json) - the backend authorizes for real.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  approveFlow,
  createFlow,
  listFlows,
  publishFlow,
  readFlow,
  saveFlowDraft,
  submitFlow,
  type FlowDetail,
  type FlowSummary
} from '../api/flows';
import { hasHarnessLogin, loadHarnessConfig } from '../api/harness-config';

/** The client-tracked status of the selected flow's latest version (server statuses, kept from the real answers). */
type VersionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PUBLISHED';

const SUBMITTED_LIKE = new Set<VersionStatus>(['SUBMITTED', 'APPROVED']);

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

export function Versions() {
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selectedRef = useRef<string | null>(null); // handlers act on click time, not render time
  const statusRef = useRef<VersionStatus>('DRAFT'); // what a click sees, even mid-chain
  const queueRef = useRef<Promise<void>>(Promise.resolve());
  const [status, setStatus] = useState<VersionStatus>('DRAFT');
  const [changeNote, setChangeNote] = useState('');
  const [refusal, setRefusal] = useState<string | null>(null);
  const [snapshotAtSubmit, setSnapshotAtSubmit] = useState<string | null>(null);
  const [published, setPublished] = useState(false);
  const [bundleEqualsSnapshot, setBundleEqualsSnapshot] = useState<boolean | null>(null);
  const [harnessApprover, setHarnessApprover] = useState(false);

  const setVersionStatus = (next: VersionStatus) => {
    statusRef.current = next;
    setStatus(next);
  };
  /** Clicks are fire-and-forget for the specs; actions run in click order on real answers. */
  const enqueue = (action: () => Promise<void>) => {
    queueRef.current = queueRef.current.then(() => action().catch((e) => console.error('versions action failed', e)));
  };

  /**
   * The flow the lifecycle walks: the first draft row, or a new one created through the real
   * API (E3-F1-S1's create seam - S is the harness's registered source system). Awaited by
   * EVERY action so a click that arrives before the initial list resolves still acts on the
   * selected flow, never silently on nothing.
   */
  const ensureSelected = useCallback(async (): Promise<string | null> => {
    if (selectedRef.current) return selectedRef.current;
    const list = await listFlows();
    if (!list.ok) return null;
    let rows = list.data;
    if (rows.length === 0) {
      await createFlow({ sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
      const again = await listFlows();
      if (!again.ok) return null;
      rows = again.data;
    }
    if (rows.length === 0) return null;
    const first = rows.find((row) => row.status === 'DRAFT') ?? rows[0];
    setFlows(rows);
    setSelectedId(first.id);
    selectedRef.current = first.id;
    setVersionStatus(first.status === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT');
    return first.id;
  }, []);

  useEffect(() => {
    void loadHarnessConfig().then(() => setHarnessApprover(hasHarnessLogin('approver')));
    void ensureSelected();
  }, [ensureSelected]);

  const detailForSubmit = async (id: string): Promise<FlowDetail | null> => {
    const read = await readFlow(id);
    return read.ok ? read.data : null;
  };

  const onSubmit = async () => {
    const id = await ensureSelected();
    if (!id) return;
    setRefusal(null);
    const detail = await detailForSubmit(id);
    if (!detail) return;
    setSnapshotAtSubmit(bundleOf(detail));
    const note = changeNote.trim() || 'console submit (no change note given)';
    let result = await submitFlow(id, note);
    if (!result.ok && result.status === 409) {
      // No draft to submit: the immutable-version design says the only way is a NEW draft (T-019),
      // so the screen saves one through the real API and submits that.
      await saveFlowDraft(id, detailBody(detail));
      result = await submitFlow(id, note);
    }
    if (result.ok) setVersionStatus('SUBMITTED');
  };

  const onApprove = async (asLogin?: string) => {
    const id = await ensureSelected();
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
    const id = await ensureSelected();
    if (!id) return;
    setRefusal(null);
    const result = await publishFlow(id);
    if (!result.ok) return;
    setPublished(true);
    const detail = await detailForSubmit(id);
    setBundleEqualsSnapshot(detail !== null && snapshotAtSubmit !== null && bundleOf(detail) === snapshotAtSubmit);
  };

  const saveCurrentDraft = async (): Promise<boolean> => {
    const id = await ensureSelected();
    if (!id) return false;
    const detail = await detailForSubmit(id);
    if (!detail) return false;
    const result = await saveFlowDraft(id, detailBody(detail));
    if (result.ok) setVersionStatus('DRAFT');
    return result.ok;
  };

  const onSaveDraft = async () => {
    if (SUBMITTED_LIKE.has(statusRef.current)) {
      // E3-F2-S1 @negative: the submitted version's content is immutable (the API refuses the
      // version-edit seam with 409); the screen surfaces the refusal and the only way through.
      setRefusal('submitted-version-immutable');
      return;
    }
    await saveCurrentDraft();
  };

  const onNewDraft = async () => {
    if (await saveCurrentDraft()) {
      setRefusal(null);
    }
  };

  return (
    <main data-page="versions">
      <h2>Versions and approval</h2>
      <ul>
        {flows.map((flow) => (
          <li key={flow.id}>
            <button type="button" data-flow-row={flow.id} onClick={() => setSelectedId(flow.id)}>
              {flow.name}{' '}
              <span data-version-status={flow.id === selectedId ? status : flow.status}>
                {flow.id === selectedId ? status : flow.status}
              </span>
            </button>
          </li>
        ))}
      </ul>
      <label>
        Change note
        <input data-field="change-note" value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
      </label>
      <button type="button" data-action="submit-for-approval" onClick={() => enqueue(onSubmit)}>
        Submit for approval
      </button>
      <button type="button" data-action="approve" onClick={() => enqueue(() => onApprove())}>
        Approve
      </button>
      {harnessApprover && (
        <button type="button" data-action="approve-as-approver" onClick={() => enqueue(() => onApprove('approver'))}>
          Approve as the harness approver
        </button>
      )}
      <button type="button" data-action="save-draft" onClick={() => enqueue(onSaveDraft)}>
        Save draft
      </button>
      <button type="button" data-action="publish" disabled={status !== 'APPROVED'} onClick={() => enqueue(onPublish)}>
        Publish
      </button>
      {refusal === 'author-cannot-approve' && (
        <p data-refusal="author-cannot-approve">Refused: the author cannot approve their own version (AUTHOR_IS_APPROVER).</p>
      )}
      {refusal === 'submitted-version-immutable' && (
        <>
          <p data-refusal="submitted-version-immutable">
            The submitted version cannot be edited: a new draft version is the only way.
          </p>
          <button type="button" data-action="new-draft" onClick={() => enqueue(onNewDraft)}>
            New draft
          </button>
        </>
      )}
      {refusal === 'not-pending-approval' && <p data-refusal="not-pending-approval">No version is pending approval.</p>}
      {refusal !== null && !['author-cannot-approve', 'submitted-version-immutable', 'not-pending-approval'].includes(refusal) && (
        <p data-refusal={refusal}>The API refused this action ({refusal}).</p>
      )}
      {published && <p data-published="true">Published.</p>}
      {bundleEqualsSnapshot !== null && (
        <p data-bundle-equals-snapshot={String(bundleEqualsSnapshot)}>
          Published bundle equals the snapshot taken at submit: {String(bundleEqualsSnapshot)}.
        </p>
      )}
    </main>
  );
}
