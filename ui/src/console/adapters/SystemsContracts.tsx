// Console adapters (week 6 mount): thin glue between the presentational screens and the shared
// query hooks. App.tsx is intentionally untouched here.
import * as React from 'react';
import { Screen } from '../Screen';
import { useSystems, useContracts, useFlows } from '../../api/hooks';
import type { SystemView } from '../../api/systems';
import type { Contract } from '../../api/contracts';
import type { SystemRow } from '../../screens/systems/types';
import type { FlowSummary } from '../../api/flows';
import SystemsScreen from '../../screens/systems/SystemsScreen';
import ContractsScreen from '../../screens/contracts/ContractsScreen';
import type { ContractRowVM } from '../../screens/contracts/ContractsScreen';

/** Return every candidate field name a flow might expose for its source/destination systems. */
function flowField(flow: unknown, ...names: string[]): unknown {
  const candidate = flow as Record<string, unknown>;
  for (const name of names) {
    if (candidate[name] !== undefined && candidate[name] !== null) return candidate[name];
  }
  return undefined;
}

/** Normalize a source/destination value (string, array, or {code|id|name}) to string labels. */
function asLabels(value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (Array.isArray(value)) {
    const out: string[] = [];
    for (const item of value) {
      if (typeof item === 'string') out.push(item);
      else if (item && typeof item === 'object') {
        const obj = item as Record<string, unknown>;
        const label = obj.code ?? obj.id ?? obj.name ?? obj.system ?? obj.systemCode ?? obj.systemId;
        if (label !== undefined && label !== null) out.push(String(label));
      } else out.push(String(item));
    }
    return out;
  }
  if (typeof value === 'string') return [value];
  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const label = obj.code ?? obj.id ?? obj.name ?? obj.system ?? obj.systemCode ?? obj.systemId;
    if (label !== undefined && label !== null) return [String(label)];
  }
  return [String(value)];
}

const SOURCE_FIELDS = ['sourceSystem', 'source', 'sourceSystemId', 'sourceSystemCode', 'sourceSystemName', 'sourceCode'];
const DESTINATION_FIELDS = [
  'destinations', 'destinationSystems', 'destination', 'destinationSystem',
  'targetSystems', 'targets', 'dests'
];

function labelsOf(flow: FlowSummary): string {
  const candidate = flow as unknown as { name?: string; code?: string; id?: string };
  return String(candidate.name ?? candidate.code ?? candidate.id ?? '');
}

function usedByForSystem(
  system: { id?: string; code?: string; name?: string },
  flows: FlowSummary[]
): string[] {
  const identities = new Set(
    [system.id, system.code, system.name]
      .filter((value): value is string => typeof value === 'string' && value.trim() !== '')
      .map((value) => value.trim().toLowerCase())
  );
  if (identities.size === 0) return [];

  const labels = new Set<string>();
  for (const flow of flows ?? []) {
    const rawFlow = flow as unknown;
    const source = flowField(rawFlow, ...SOURCE_FIELDS);
    const destinations = flowField(rawFlow, ...DESTINATION_FIELDS);
    const sourceLabels = asLabels(source);
    const destinationLabels = asLabels(destinations);

    const matches = [...sourceLabels, ...destinationLabels].some((label) =>
      identities.has(String(label).trim().toLowerCase())
    );
    if (matches) {
      const label = labelsOf(flow);
      if (label) labels.add(label);
    }
  }
  return Array.from(labels);
}

function systemRow(raw: SystemView, flows: FlowSummary[] = []): SystemRow {
  const candidate = raw as unknown as {
    id?: string;
    code?: string;
    name?: string;
    status?: string;
    type?: string | null;
    protocol?: string;
  };
  // TODO: latencyMs and health status are not carried by SystemView/listSystems yet; keep them
  // as TODO until the backend exposes them. Do NOT invent per-system latency.
  return {
    id: String(candidate.id ?? candidate.code ?? ''),
    name: String(candidate.name ?? candidate.code ?? ''),
    code: String(candidate.code ?? candidate.id ?? ''),
    status: (candidate.status ?? 'Unknown') as SystemRow['status'],
    protocol: String(candidate.protocol ?? candidate.type ?? 'REST API') as SystemRow['protocol'],
    // usedBy is derived client-side from the flow list's source/destination fields when present;
    // flows that do not expose those fields are skipped (no per-flow fetch / follow-up).
    usedBy: usedByForSystem(candidate, flows)
  };
}

function contractRow(raw: Contract, index: number): ContractRowVM {
  const candidate = raw as unknown as {
    code?: string;
    contractId?: string;
    id?: string;
    name?: string;
    format?: string;
    versions?: { versionLabel?: string; createdAt?: string }[];
  };
  const first = (candidate.versions ?? [])[0];
  return {
    name: String(candidate.name ?? candidate.code ?? candidate.contractId ?? candidate.id ?? `contract-${index}`),
    version: String(first?.versionLabel ?? candidate.code ?? ''),
    format: (candidate.format ?? 'XML') as ContractRowVM['format'],
    flowsUsing: 0,
    updated: String(first?.createdAt ?? '')
  };
}

export function SystemsRoute(): React.ReactElement {
  const systems = useSystems();
  // Flows are loaded once here and their (optional) source/destination fields are used to derive each
  // system's `usedBy` list; we deliberately do NOT fetch per-flow detail for systems lacking them.
  const flows = useFlows();
  const flowList = flows.data ?? [];
  return (
    <Screen title="Systems">
      <SystemsScreen
        systems={systems.data ? systems.data.map((system) => systemRow(system, flowList)) : null}
        loading={systems.loading || flows.loading}
        error={systems.error ?? systems.refusal ?? flows.error ?? flows.refusal ?? ''}
        stale={systems.stale || flows.stale}
        onRetry={() => { systems.reload(); flows.reload(); }}
      />
    </Screen>
  );
}

export function ContractsRoute(): React.ReactElement {
  const contracts = useContracts();
  return (
    <Screen title="Contracts">
      <ContractsScreen
        rows={contracts.data ? contracts.data.map(contractRow) : []}
        loading={contracts.loading}
        error={contracts.error ?? contracts.refusal ?? null}
        onRetry={contracts.reload}
      />
    </Screen>
  );
}

export default { SystemsRoute, ContractsRoute };
