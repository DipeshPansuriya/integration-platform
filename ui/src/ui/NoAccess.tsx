// Row 562: the shared no-access alert (role=alert, Bootstrap-style op-alert like the other refusals).
// Hook-free, so a view renders it in place of its table. `refusal` keeps each view's existing data-refusal hook.
import { NO_ACCESS_COPY } from './no-access';

export function NoAccess({ refusal }: { refusal: string }) {
  return (
    <p role="alert" className="op-alert op-alert-danger" data-no-access="true" data-refusal={refusal}>
      {NO_ACCESS_COPY}
    </p>
  );
}
