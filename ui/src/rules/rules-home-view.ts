// Lane A (B27, audit A9): what the Rules home list shows - a failed fetch is an error with Retry,
// never the "No rule sets" empty copy.

import { failureText } from '../pages/flow-save';

export type HomeListState = 'error' | 'empty' | 'empty-kind' | 'rows';

export function homeListState(input: { error: string | null; total: number; shown: number }): HomeListState {
  if (input.error !== null) return 'error';
  if (input.shown > 0) return 'rows';
  return input.total === 0 ? 'empty' : 'empty-kind';
}

/** null for an ok answer; otherwise one worded sentence naming what could not load. */
export function homeLoadError(what: string, answer: { ok: boolean; status?: number }): string | null {
  return answer.ok ? null : failureText(`load the ${what}`, answer.status ?? 0);
}
