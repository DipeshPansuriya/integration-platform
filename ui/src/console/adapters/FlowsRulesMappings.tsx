// Console adapters (week 6 mount): thin glue between the presentational screens and the shared
// query hooks. App.tsx is intentionally untouched here; these are consumed by routing in a later slice.

import { useFlows, useRuleSets } from '../../api/hooks';
import * as React from 'react';
import { Screen } from '../Screen';
import FlowsScreen from '../../screens/flows/FlowsScreen';
import RulesScreen from '../../screens/rules/RulesScreen';
import MappingsScreen from '../../screens/mappings/MappingsScreen';
import type { FlowRow } from '../../screens/flows/types';
import type { RuleRowVM } from '../../screens/rules/RulesScreen';
import type { MappingsScreenProps } from '../../screens/mappings/MappingsScreen';

export interface FlowsRouteProps {
  /** Called when a flow card is opened. */
  onOpenFlow?: (flow: FlowRow) => void;
  /** Called when the user asks to create a new flow. */
  onNewFlow?: () => void;
}

export function FlowsRoute(props: FlowsRouteProps = {}): React.ReactElement {
  const flows = useFlows();
  const rows: FlowRow[] | null = flows.data
    ? flows.data.map((flow) => ({
        id: flow.id,
        code: flow.code,
        name: flow.name,
        status: flow.status as FlowRow['status']
      }))
    : null;
  const error = flows.error ?? flows.refusal ?? '';

  return (
    <Screen title="Flows">
      <FlowsScreen
        flows={rows}
        loading={flows.loading}
        error={error}
        stale={flows.stale}
        onRetry={flows.reload}
        onOpenFlow={props.onOpenFlow}
        onNewFlow={props.onNewFlow}
      />
    </Screen>
  );
}

export interface RulesRouteProps {
  /** Flow whose rule sets are listed. When null/omitted the first available flow is used. */
  flowId?: string | null;
}

export function RulesRoute(props: RulesRouteProps = {}): React.ReactElement {
  const flows = useFlows();
  const flowId = props.flowId ?? flows.data?.[0]?.id ?? null;
  const ruleSets = useRuleSets(flowId);

  // TODO: map RuleSet content (ZEN/JDM) to RuleRowVM columns/rows, hit rates and timestamps once
  //       the rule-set summary shape exposes them; only the name is available from the list today.
  const rows: RuleRowVM[] | undefined = ruleSets.data?.map((ruleSet) => ({
    name: ruleSet.name,
    conditions: 0,
    outputs: 0,
    hitRate: 0,
    updated: ''
  }));

  return (
    <Screen title="Rules">
      <RulesScreen
        rows={rows}
        loading={ruleSets.loading}
        error={ruleSets.error ?? ruleSets.refusal}
        onRetry={ruleSets.reload}
      />
    </Screen>
  );
}

export type MappingsRouteProps = Partial<MappingsScreenProps> & { flowId?: string | null };

export function MappingsRoute(props: MappingsRouteProps = {}): React.ReactElement {
  // TODO: wire the mappings list endpoint when it lands (no `useMappings` hook exists yet).
  // TODO: provide contracts/options and rule sets (via useFlows/useRuleSets(flowId)) for the
  //       New Mapping drawer; the screen's `contracts` and option lists are not populated yet.
  const { flowId, ...screenProps } = props;
  void flowId;

  return (
    <Screen title="Mappings">
      <MappingsScreen {...screenProps} />
    </Screen>
  );
}
