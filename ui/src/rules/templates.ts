// T-025 (E6-F1-S1, lane D): the starter decision table a new rule set of each kind is created with -
// input node -> one decision table -> output node, the shape jdm-editor emits. They are the same three
// tables lane B's API suite accepts (platform-config test Jdm.java), so a configurator starts from a
// working example and edits cells instead of facing an empty grid. Also the seam contract's fixtures.

import type { ZenDecisionTable, ZenJdm } from './DecisionTableEditorAdapter';

export const RULE_KINDS = ['VALIDATION', 'ROUTING', 'LOOKUP'] as const;
export type RuleKind = (typeof RULE_KINDS)[number];

export const KIND_LABELS: Record<RuleKind, string> = {
  VALIDATION: 'Validation rule',
  ROUTING: 'Conditional routing rule',
  LOOKUP: 'Lookup table'
};

/** Row 310 T102 (10-screens s7): a kind badge carries its word; registered kinds use the info tint, any
 * other kind the neutral one and shows its own code (DECISION etc keep their words). Never colour alone. */
export function kindBadge(kind: string): { word: string; tone: 'info' | 'neutral' } {
  const word = KIND_LABELS[kind as RuleKind];
  return word ? { word, tone: 'info' } : { word: kind, tone: 'neutral' };
}

const TABLES: Record<RuleKind, ZenDecisionTable> = {
  VALIDATION: {
    hitPolicy: 'collect',
    inputs: [
      { id: 'c1', name: 'Amount', field: 'amount' },
      { id: 'c2', name: 'Currency', field: 'currency' },
      { id: 'c3', name: 'Reference', field: 'reference' }
    ],
    outputs: [
      { id: 'o1', name: 'Field', field: 'violation.field' },
      { id: 'o2', name: 'Code', field: 'violation.code' },
      { id: 'o3', name: 'Message', field: 'violation.message' }
    ],
    rules: [
      { _id: 'r1', c1: '> 100', c2: '', c3: '', o1: '"amount"', o2: '"Z_AMOUNT_TOO_HIGH"', o3: '"amount must not exceed 100"' },
      { _id: 'r2', c1: '', c2: '"XXX"', c3: '', o1: '"currency"', o2: '"A_CURRENCY_UNKNOWN"', o3: '"currency XXX is not accepted"' },
      { _id: 'r3', c1: '', c2: '', c3: '""', o1: '"reference"', o2: '"M_REFERENCE_REQUIRED"', o3: '"reference is required"' },
      { _id: 'r4', c1: '< 0', c2: '', c3: '', o1: '"amount"', o2: '"NEGATIVE"', o3: '"amount must not be negative"' }
    ]
  },
  ROUTING: {
    hitPolicy: 'first',
    inputs: [{ id: 'c1', name: 'Country', field: 'country' }],
    outputs: [{ id: 'o1', name: 'Target', field: 'target' }],
    rules: [
      { _id: 'r1', c1: '"NL"', o1: '"dst-eu"' },
      { _id: 'r2', c1: '', o1: '"dst-default"' }
    ]
  },
  LOOKUP: {
    hitPolicy: 'first',
    inputs: [{ id: 'c1', name: 'Code', field: 'code' }],
    outputs: [{ id: 'o1', name: 'Name', field: 'name' }],
    rules: [
      { _id: 'r1', c1: '"NL"', o1: '"Netherlands"' },
      { _id: 'r2', c1: '"DE"', o1: '"Germany"' }
    ]
  }
};

/** A sample payload the test panel starts with, one that a row of the starter table matches. */
const SAMPLES: Record<RuleKind, unknown> = {
  VALIDATION: { amount: 150, currency: 'XXX', reference: '' },
  ROUTING: { country: 'NL' },
  LOOKUP: { code: 'NL' }
};

export function starterJdm(kind: RuleKind): ZenJdm {
  return {
    nodes: [
      { id: 'request', type: 'inputNode', name: 'Request', position: { x: 0, y: 0 } },
      { id: 'checks', type: 'decisionTableNode', name: 'Checks', position: { x: 240, y: 0 }, content: structuredClone(TABLES[kind]) },
      { id: 'response', type: 'outputNode', name: 'Response', position: { x: 480, y: 0 } }
    ],
    edges: [
      { id: 'e1', sourceId: 'request', targetId: 'checks', type: 'edge' },
      { id: 'e2', sourceId: 'checks', targetId: 'response', type: 'edge' }
    ]
  };
}

export function sampleInput(kind: string): string {
  return JSON.stringify(SAMPLES[kind as RuleKind] ?? {});
}
