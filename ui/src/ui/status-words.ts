// Row 572 S0 (spec 4.6): the ONE status vocabulary. Every enum the console shows to a person goes through
// statusWord(kind, value) -> { word, tone }. The word is the carrier, the tone only repeats it (never colour alone).
// Tones: success = finished good, info = in progress / nothing to do, warn = the user has a next step,
// danger = something to fix, neutral = no data. No React, no fetch - unit-tested under node.

export type Tone = 'success' | 'info' | 'warn' | 'danger' | 'neutral';
export type StatusWord = { word: string; tone: Tone };

const w = (word: string, tone: Tone): StatusWord => ({ word, tone });

const TABLE = {
  /** A flow version's lifecycle status (FlowAdminService). */
  flowVersion: {
    DRAFT: w('Draft', 'warn'),
    SUBMITTED: w('Submitted', 'info'),
    APPROVED: w('Approved', 'success'),
    PUBLISHED: w('Published', 'success')
  },
  /** A contract version's status (openapi.d.ts ContractVersion.status). */
  contractVersion: {
    DRAFT: w('Draft', 'warn'),
    PENDING_APPROVAL: w('Waits for approval', 'warn'),
    APPROVED: w('Approved', 'success'),
    RETIRED: w('Retired', 'neutral')
  },
  /** A message's state (ACCEPTED/ROUTED/ROUTE_FAILED) and a delivery's state (openapi.d.ts, ADR-010 section 6) - disjoint names. */
  state: {
    ACCEPTED: w('Accepted', 'info'),
    ROUTED: w('Routed', 'success'),
    ROUTE_FAILED: w('Route failed', 'danger'),
    PENDING: w('Queued', 'info'),
    HELD: w('On hold', 'neutral'),
    SENDING: w('Sending', 'info'),
    RETRY_WAIT: w('Retrying', 'info'),
    DUE: w('Ready to retry', 'info'),
    DELIVERED: w('Delivered', 'success'),
    DEAD_LETTERED: w('Gave up', 'danger')
  },
  /** One pipeline stage row (message-timeline.ts StageStatus). */
  stage: {
    done: w('Done', 'success'),
    running: w('Running', 'info'),
    failed: w('Failed', 'danger'),
    'not-reached': w('Not reached', 'neutral'),
    'not-recorded': w('Not recorded', 'neutral')
  },
  /** A delivery attempt's outcome class (message-timeline.ts). */
  outcome: {
    SUCCESS: w('Success', 'success'),
    TRANSIENT: w('Transient', 'warn'),
    AMBIGUOUS: w('Ambiguous', 'warn'),
    PERMANENT: w('Permanent', 'danger')
  },
  /** The flow's ordering setting (ADR-011). */
  ordering: {
    PER_KEY: w('Per key', 'neutral'),
    NONE: w('None', 'neutral')
  },
  /** A contract's role (openapi.d.ts). */
  role: {
    SOURCE_REQUEST: w('Source request', 'neutral'),
    SOURCE_RESPONSE: w('Source response', 'neutral'),
    TARGET_REQUEST: w('Target request', 'neutral'),
    TARGET_RESPONSE: w('Target response', 'neutral')
  }
} as const satisfies Record<string, Record<string, StatusWord>>;

export type StatusKind = keyof typeof TABLE;

/** kind -> every value that has a word (the unit test walks this and the openapi.d.ts unions). */
export const STATUS_VALUES: Record<StatusKind, string[]> = Object.fromEntries(
  Object.entries(TABLE).map(([kind, values]) => [kind, Object.keys(values)])
) as Record<StatusKind, string[]>;

const lookup = (kind: StatusKind, value: string): StatusWord | undefined =>
  Object.prototype.hasOwnProperty.call(TABLE[kind], value) ? (TABLE[kind] as Record<string, StatusWord>)[value] : undefined;

export function isKnownStatus(kind: StatusKind | string, value: string | null | undefined): boolean {
  return kind in TABLE && value != null && lookup(kind as StatusKind, value) !== undefined;
}

/** "WEIRD_THING" -> "Weird thing": a value the server added that has no word yet is still readable, never shouted. */
function humanize(value: string): string {
  const spaced = value.toLowerCase().replace(/[_-]+/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** The word and tone for an enum value; null/undefined -> "-" neutral; an unmapped value is humanized, neutral. */
export function statusWord(kind: StatusKind, value: string | null | undefined): StatusWord {
  if (value == null) return w('-', 'neutral');
  return lookup(kind, value) ?? w(humanize(value), 'neutral');
}
