// Lane A (B27, audit A4/A7): wording for the Versions lifecycle panel - failures, busy labels and the
// polite status announcement - kept pure so it is testable without a DOM.

import { failureText } from './flow-save';

const TASK: Record<string, string> = {
  'save-draft': 'save the draft',
  'submit-for-approval': 'submit the version for approval',
  approve: 'approve the version',
  publish: 'publish the version',
  'new-draft': 'start a new draft',
  open: 'open the version'
};

/** A worded sentence for a failed lifecycle request: what failed and what to do. */
export const actionFailure = (action: string, status: number): string => failureText(TASK[action] ?? 'complete that action', status);

/** The label a button shows while its own request is in flight. */
export const PENDING_LABEL: Record<string, string> = {
  'save-draft': 'Saving...',
  'submit-for-approval': 'Submitting...',
  approve: 'Approving...',
  publish: 'Publishing...',
  'new-draft': 'Starting...'
};

const ANNOUNCE: Record<string, string> = {
  DRAFT: 'Version status: Draft. It can be edited and submitted for approval.',
  SUBMITTED: 'Version status: Submitted. Waiting for approval.',
  APPROVED: 'Version status: Approved. It can now be published.',
  PUBLISHED: 'Version status: Published. It is read-only.'
};

export const statusAnnouncement = (status: string): string => ANNOUNCE[status] ?? '';
