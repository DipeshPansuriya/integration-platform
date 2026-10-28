// Row 562: the ONE wording of the "you may not open this" state a 403 shows on the operator views
// (queue, failures, activity, delivery report). Plain words - never the refusal code or the HTTP status.
// UX only: the backend's role check is the control; nothing here hides data as a security measure.
export const NO_ACCESS_COPY = 'You do not have access to this page. Ask an administrator if you need it.';

/** A refusal is the no-access state exactly when the API answered 403. */
export function isNoAccess(status: number): boolean {
  return status === 403;
}
