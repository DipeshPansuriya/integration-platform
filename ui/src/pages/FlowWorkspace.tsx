// Row 575 S4 (guided flow, spec 4.4): the flow workspace header - breadcrumb, status badge, the six-step setup stepper, the tab
// links and (after publish) the "waiting for the first message" line. It reads the flow and its routes (two calls) and nothing
// else until publish; the form below it is the unchanged Setup tab. Every link opts in to the editor's unsaved-edit guard
// (data-leaves-editor). UX only: the server answers every action.

import { useEffect, useRef, useState } from 'react';
import { readFlow, type FlowDetail } from '../api/flows';
import { api } from '../api/client';
import { queueSummary } from '../api/queue';
import { loadRoutes, type FlowRoute } from '../mapping/routesList';
import { Breadcrumb } from '../ui/Breadcrumb';
import { statusWord } from '../ui/status-words';
import { flowStepper, flowTabs, type WorkspaceRoles } from './flow-stepper';
import { createFirstMessagePoll, type PollCheck, type PollState } from './first-message-poll';
import { todayUtc } from './failures-view';

type Loaded = { flow: FlowDetail; routes: FlowRoute[] };

const POLL_WORDS: Record<PollState, string> = {
  waiting: 'No message has reached this flow today (UTC) yet. This page checks every 15 seconds for up to 10 minutes.',
  arrived: 'A message reached this flow today (UTC).',
  'stopped-time': 'Stopped checking after 10 minutes.',
  'stopped-errors': 'Could not check for messages.',
  'stopped-hidden': 'Checking is paused while this tab is hidden.'
};

export function FlowWorkspace({ flowId, roles }: { flowId: string; roles: WorkspaceRoles }) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState(false);
  const [pollState, setPollState] = useState<PollState>('waiting');
  const poll = useRef<ReturnType<typeof createFirstMessagePoll> | null>(null);

  useEffect(() => {
    let current = true;
    setLoaded(null);
    setFailed(false);
    Promise.all([readFlow(flowId), loadRoutes(api, flowId)])
      .then(([flow, routes]) => {
        if (!current) return;
        if (!flow.ok) return void setFailed(true);
        setLoaded({ flow: flow.data, routes: routes.state === 'ok' ? routes.routes : [] });
      })
      .catch(() => {
        if (current) setFailed(true);
      });
    return () => {
      current = false;
    };
  }, [flowId]);

  const published = loaded?.flow.status === 'PUBLISHED' && roles.canReadMessages;
  useEffect(() => {
    if (!published) return;
    const check = async (): Promise<PollCheck> => {
      const answer = await queueSummary({ flowId, date: todayUtc(new Date()) });
      if (!answer.ok) return 'error';
      return answer.data.some((row) => row.flowId === flowId && row.queued + row.processed + row.converted + row.failed > 0) ? 'arrived' : 'none';
    };
    const p = createFirstMessagePoll({
      check,
      onState: setPollState,
      now: () => Date.now(),
      setTimer: (fn, ms) => {
        const id = setTimeout(fn, ms);
        (id as { unref?: () => void }).unref?.(); // node (tests) must not wait for it; browsers return a number
        return id;
      },
      clearTimer: (id) => clearTimeout(id as ReturnType<typeof setTimeout>),
      isHidden: () => typeof document !== 'undefined' && document.hidden
    });
    poll.current = p;
    p.start({ immediate: true });
    const onVisible = () => {
      // Only a hidden stop resumes; arrived / time / error stops stay stopped (they have their own Check again).
      if (typeof document !== 'undefined' && !document.hidden) p.resumeFromHidden();
    };
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible);
    return () => {
      p.stop();
      poll.current = null;
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible);
    };
  }, [published, flowId]);

  if (failed) {
    return <p className="flows-muted" data-flow-workspace-error>The setup progress could not be loaded. The form below still works.</p>;
  }
  if (!loaded) return null;

  const { flow, routes } = loaded;
  const { steps } = flowStepper({ flow, routes, roles });
  const tabs = flowTabs({ flowId, routes, roles });
  const badge = statusWord('flowVersion', flow.status);

  return (
    <section className="flow-workspace" data-flow-workspace>
      <Breadcrumb items={[{ label: 'Flows', href: '#/flows', leavesEditor: true }, { label: flow.name || flow.code }]} />
      <p>
        Status: <span className={`badge badge-${badge.tone}`} data-flow-status>{badge.word}</span>
      </p>
      <ol className="home-steps" data-flow-stepper aria-label="Setup progress">
        {steps.map((step) => (
          <li key={step.key} className={`home-step home-step-${step.state}`} data-flow-step={step.key} aria-current={step.state === 'next' ? 'step' : undefined}>
            <span className="home-step-mark" aria-hidden="true">{step.mark}</span>
            <span className="home-step-label">{step.n} {step.label}</span>
            <span className="home-step-state">{step.stateWord}</span>
            <span className="home-step-note">{step.note}</span>
            {step.action && (
              <a className={step.state === 'next' ? 'btn btn-primary' : 'btn'} href={step.action.href} data-leaves-editor="true">{step.action.label}</a>
            )}
          </li>
        ))}
      </ol>
      <nav aria-label="Flow sections" className="op-tabs">
        {tabs.map((tab) => (
          <a key={tab.key} href={tab.href} data-flow-tab={tab.key} data-leaves-editor="true" aria-current={tab.current ? 'page' : undefined}>
            {tab.label}
          </a>
        ))}
      </nav>
      {published && (
        <div className="op-panel" data-first-message={pollState}>
          <p role="status" className="dashboard-muted">{POLL_WORDS[pollState]}</p>
          {pollState === 'arrived' && (
            <a className="btn" href={`#/failures?flow=${encodeURIComponent(flowId)}`} data-leaves-editor="true">Open failed messages</a>
          )}
          {(pollState === 'stopped-time' || pollState === 'stopped-errors') && (
            <button type="button" className="btn" onClick={() => poll.current?.start({ immediate: true })}>Check again</button>
          )}
        </div>
      )}
    </section>
  );
}
