// Row 575 (S1 of the guided flow): the two new menu targets exist from S1 so the six-item menu has no dead link. Each is a
// worded stub that points at today's screen; S3 (Messages landing) and S5 (Approvals list) replace the bodies. Hook-free.
import { PageHeader } from '../ui/PageHeader';

export function MessagesLanding() {
  return (
    <section data-page="messages-landing">
      <PageHeader title="Messages" breadcrumb={[{ label: 'Messages' }]} next={<>open <a href="#/failures">Failures</a> to see what needs attention.</>} />
      <p>To follow one message, open it from the Failures list.</p>
    </section>
  );
}

export function ApprovalsLanding() {
  return (
    <section data-page="approvals-landing">
      <PageHeader title="Approvals" breadcrumb={[{ label: 'Approvals' }]} next={<>open <a href="#/versions">Versions</a> to approve a flow version.</>} />
      <p>Flow versions and contract versions that wait for approval are listed on their own screens for now.</p>
    </section>
  );
}
