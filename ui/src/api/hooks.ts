// W4 (iPaaS Console plan, approach #4): "One API client and one query hook per resource.
// Live screens (Dashboard, Queue, Failures) poll on an interval agreed with backend in week 1."
// One generic resource hook plus a thin typed hook per resource; every hook answers the same
// Resource shape so screen lanes learn one surface. All fetching goes through the existing
// api/* modules (and their ApiAnswer envelopes) - no endpoint invented here.

import { useEffect, useRef, useState } from 'react';
import { listFlows, readFlow, type ApiAnswer, type FlowDetail, type FlowSummary } from './flows';
import { listSystems, type SystemView } from './systems';
import { listContracts, type Contract } from './contracts';
import { queueSummary, type QueueRow } from './queue';
import { listActivity, type ActivityPage } from './activity';
import { deliveryReport, type DailyCounts } from './reports';
import { messageFailures, messageTimeline, type Failure, type Timeline } from './messages';
import { replayDelivery, retryDelivery, type OperatorAnswer } from './operator';
import { api } from './client';
import type { SavedMappingAnswer } from '../mapping/graph';
import { listRuleSets, type RuleSet } from '../rules/ruleSets';
import {
  contractsInDraft,
  flowCounts,
  recentVersions,
  systemsByDirection } from '../pages/dashboard-counts';

/** Interim live-screen poll interval until backend agrees on one in week 1 (plan W4). */
export const POLL_MS = 20_000;

export type AnswerFailure = { status: number; error?: string; refusal?: string };

/** What every screen hook returns - one shape, one learning cost for the four screen lanes. */
export type Resource<T> = {
  /** The last good answer; stays set (and `stale` turns on) when a poll fails. */
  data: T | null;
  /** First load has not answered yet (no data, nothing on screen but skeletons). */
  loading: boolean;
  /** A poll (or reload) is in flight while older data stays on screen. */
  refreshing: boolean;
  /** The latest poll failed but older data is still shown, marked stale (10-screens states). */
  stale: boolean;
  status: number | null;
  error: string | null;
  refusal: string | null;
  /** Manual fetch again; also re-run to re-arm the poll from now. */
  reload: () => void;
};

const EMPTY: Resource<never> = {
  data: null, loading: true, refreshing: false, stale: false,
  status: null, error: null, refusal: null, reload: () => {}
};

const RESOLVED = { ok: true as const, data: null };

export function useResource<T, A extends ApiAnswer<T>>(
  loader: () => Promise<A>,
  deps: unknown[],
  pollMs = 0
): Resource<T> {
  const loaderRef = useRef(loader);
  loaderRef.current = loader; // latest loader every render; effect deps decide re-runs
  const [state, setState] = useState<Resource<T>>(EMPTY as unknown as Resource<T>);
  const [tick, setTick] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    const run = ++latest.current;
    const fetch = (initial: boolean) => {
      if (initial) setState((s) => ({ ...s, loading: true }));
      else setState((s) => ({ ...s, refreshing: true }));
      loaderRef.current().then((answer) => {
        if (run !== latest.current) return; // superseded by a newer run
        const fail: AnswerFailure | null = answer.ok
          ? null
          : { status: answer.status, error: answer.error, refusal: answer.refusal };
        setState((s) => ({
          ...s,
          loading: false,
          refreshing: false,
          data: answer.ok ? (answer.data as T) : s.data,
          stale: answer.ok ? false : s.data !== null,
          status: fail ? fail.status : null,
          error: fail ? (fail.error ?? null) : null,
          refusal: fail ? (fail.refusal ?? null) : null
        }));
      }).catch(() => {
        if (run !== latest.current) return;
        setState((s) => ({
          ...s,
          loading: false,
          refreshing: false,
          stale: s.data !== null,
          status: 0,
          error: 'The request did not answer'
        }));
      });
    };
    fetch(true);
    let timer: ReturnType<typeof setInterval> | null = null;
    if (pollMs > 0) timer = setInterval(() => fetch(false), pollMs);
    return () => {
      latest.current += 1; // unmount/supersede: in-flight answers are dropped
      if (timer) clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, pollMs]);

  return { ...state, reload: () => setTick((t) => t + 1) };
}

/* Per-resource hooks - the W4 table, one per resource, all wrapping existing api/* functions. */

export function useFlows(pollMs = 0): Resource<FlowSummary[]> {
  return useResource(listFlows, [], pollMs);
}

/** A flow's detail; `flowId` null (the list/landing) answers ok with data null, no fetch. */
export function useFlow(flowId: string | null): Resource<FlowDetail> {
  return useResource<FlowDetail, ApiAnswer<FlowDetail>>(
    () => (flowId === null ? Promise.resolve(RESOLVED as ApiAnswer<FlowDetail>) : readFlow(flowId as string)),
    [flowId]
  );
}

export function useSystems(): Resource<SystemView[]> {
  return useResource(listSystems, []);
}

export function useContracts(): Resource<Contract[]> {
  return useResource(listContracts, []);
}

export function useQueue(
  params?: { flowId?: string; date?: string },
  pollMs = POLL_MS
): Resource<QueueRow[]> {
  const key = JSON.stringify(params ?? {});
  return useResource(() => queueSummary(params ?? undefined), [key], pollMs);
}

export function useFailures(
  params?: { flowId?: string; date?: string },
  pollMs = POLL_MS
): Resource<Failure[]> {
  const key = JSON.stringify(params ?? {});
  return useResource(() => messageFailures(params ?? undefined), [key], pollMs);
}

export function useActivity(query: Record<string, string | number>): Resource<ActivityPage> {
  const key = JSON.stringify(query);
  return useResource(() => listActivity(query), [key]);
}

export function useDeliveryReport(params: { day: string }): Resource<DailyCounts[]> {
  const key = JSON.stringify(params);
  return useResource(() => deliveryReport(params), [key]);
}

export function useRuleSets(flowId: string | null): Resource<RuleSet[]> {
  return useResource(
    () => (flowId === null ? Promise.resolve(RESOLVED as unknown as ApiAnswer<RuleSet[]>) : listRuleSets(flowId as string)),
    [flowId]
  );
}

export function useTimeline(trackingId: string | null): Resource<Timeline> {
  return useResource<Timeline, ApiAnswer<Timeline>>(
    () => (trackingId === null ? Promise.resolve(RESOLVED as ApiAnswer<Timeline>) : messageTimeline(trackingId as string)),
    [trackingId]
  );
}

/* Mapping - the graph screen's pair load. Designers fetch this inline in MappingDesigner.tsx;
   here the same typed openapi-fetch call is exposed as a hook without re-shaping it. */

export type UseMappingResult = Resource<SavedMappingAnswer> & { missing: boolean };

async function fetchMapping(routeId: string): Promise<ApiAnswer<SavedMappingAnswer>> {
  const { data, error, response } = await api.GET('/api/v1/admin/routes/{routeId}/mapping', {
    params: { path: { routeId } }
  });
  if (data) return { ok: true, data: data as SavedMappingAnswer };
  const status = response?.status ?? 0;
  const read = typeof error === 'object' && error !== null ? (error as { error?: string; refusal?: string }) : {};
  return {
    ok: false,
    status,
    error: status === 404 ? 'not found' : (read.error ?? undefined),
    refusal: read.refusal
  };
}

/** 404 from the mapping endpoint is the same answer for an unknown route and another tenant's
    (ADR-011 Decision 4); screens treat `missing` as "nothing drawn yet", never as an error. */
export function useMapping(routeId: string | null): UseMappingResult {
  const res = useResource<SavedMappingAnswer, ApiAnswer<SavedMappingAnswer>>(
    () => (routeId === null ? Promise.resolve(RESOLVED as ApiAnswer<SavedMappingAnswer>) : fetchMapping(routeId as string)),
    [routeId]
  );
  return { ...res, missing: res.status === 404 };
}

/* Dashboard counts - composed from three list hooks + the pure helpers the Dashboard already
   unit-tests (pages/dashboard-counts.ts). No new endpoint. */

export type DashboardCounts = {
  pendingApproval: number | null;
  drafts: number | null;
  systems: { total: number | null; inbound: number | null; outbound: number | null };
  contractsDraft: number | null;
  recentVersions: FlowSummary[];
};

export type DashboardState = {
  counts: DashboardCounts;
  flows: Resource<FlowSummary[]>;
  systems: Resource<SystemView[]>;
  contracts: Resource<Contract[]>;
  failed: boolean;
  reload: () => void;
};

export function useDashboardCounts(pollMs = POLL_MS): DashboardState {
  const flows = useFlows(pollMs);
  const systems = useSystems();
  const contracts = useContracts();
  const flowList = flows.data;
  const systemList = systems.data;
  const contractList = contracts.data;
  const counts: DashboardCounts = {
    pendingApproval: flowList ? flowCounts(flowList).pendingApproval : null,
    drafts: flowList ? flowCounts(flowList).drafts : null,
    systems: {
      total: systemList ? systemsByDirection(systemList).total : null,
      inbound: systemList ? systemsByDirection(systemList).inbound : null,
      outbound: systemList ? systemsByDirection(systemList).outbound : null
    },
    contractsDraft: contractList ? contractsInDraft(contractList) : null,
    recentVersions: flowList ? recentVersions(flowList) : []
  };
  const failed = [flows, systems, contracts].some((r) => !isAnswerOK(r));
  return {
    counts,
    flows,
    systems,
    contracts,
    failed,
    reload: () => { flows.reload(); systems.reload(); contracts.reload(); }
  };
}

const isAnswerOK = (r: Resource<unknown>) => r.error === null && r.refusal === null && r.status === null;

/* Operator actions (Failures screen): fire-and-report, not polled. */

export async function replay(deliveryId: string): Promise<OperatorAnswer> {
  return replayDelivery(deliveryId);
}

export async function retry(deliveryId: string): Promise<OperatorAnswer> {
  return retryDelivery(deliveryId);
}