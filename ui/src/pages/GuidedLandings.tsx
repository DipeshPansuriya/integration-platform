// Row 575 (S1/S3 of the guided flow): the two new menu targets. Messages (S3) is the tracking-id search and the two tabs; Approvals is its own screen (Approvals.tsx, S5). Hook-light, tokens-only.
import { useState } from 'react';
import { PageHeader } from '../ui/PageHeader';
import { TRACKING_ID_HINT, trackingHash } from './home-steps';

export function MessagesLanding() {
  const [value, setValue] = useState('');
  const [bad, setBad] = useState(false);
  return (
    <section data-page="messages-landing">
      <PageHeader title="Messages" breadcrumb={[{ label: 'Messages' }]} next={<>paste a tracking id to follow one message, or open Needs attention.</>} />
      <form
        className="home-search"
        onSubmit={(event) => {
          event.preventDefault();
          const target = trackingHash(value);
          setBad(target === null);
          if (target !== null) window.location.hash = target;
        }}
      >
        <label htmlFor="messages-tracking">Tracking id</label>
        <input
          id="messages-tracking"
          className="input"
          value={value}
          aria-invalid={bad}
          aria-describedby={bad ? 'messages-tracking-hint' : undefined}
          onChange={(event) => setValue(event.target.value)}
        />
        <button type="submit" className="btn btn-primary">Search</button>
      </form>
      {/* Always mounted (empty first) so the hint is announced when it appears. */}
      <p id="messages-tracking-hint" className="dashboard-muted" role="status">{bad ? TRACKING_ID_HINT : ''}</p>
      {/* Tabs are links (spec section 5). The Needs attention tab opens today's Failures screen; the menu's own Failures entry keeps data-nav until S10. */}
      <nav aria-label="Messages views" data-message-tabs className="op-tabs">
        <a href="#/messages" aria-current="page">Results</a>
        <a href="#/failures">Needs attention</a>
      </nav>
      <p className="dashboard-muted">
        Searching by what the business knows (for example an appointment number) and listing recent messages need a new read service and are not available yet. Use the tracking id, or open Needs attention to see failed messages.
      </p>
    </section>
  );
}
