// Lane A (B27, audit A5/A6): what the mapping designer does with the answer of its first load.
// A failed load is an error with Retry and a disabled Save - never an empty mapping to overwrite
// the stored one - and every route opens a fresh designer.

import type { SavedMappingAnswer } from '../mapping/graph';
import { failureText } from './flow-save';

export type LoadAnswer = { data?: unknown; error?: unknown; response?: { ok?: boolean; status?: number } };

export type MappingLoad =
  | { kind: 'loaded'; saved: SavedMappingAnswer }
  | { kind: 'missing' }
  | { kind: 'failed'; text: string };

export function classifyMappingLoad(answer: LoadAnswer): MappingLoad {
  const status = answer.response?.status ?? 0;
  // 404: another tenant's route (ADR-011 Decision 4) or an unknown id - the same empty state.
  if (status === 404) return { kind: 'missing' };
  if (answer.response?.ok) return { kind: 'loaded', saved: (answer.data ?? {}) as SavedMappingAnswer };
  return { kind: 'failed', text: failureText('open the mapping', status) };
}

/** Save needs an open route whose load finished cleanly: while loading or after a failure it is off. */
export function canSaveMapping(state: { routeId: string | null; loaded: boolean; loadError: string | null }): boolean {
  return state.routeId !== null && state.loaded && state.loadError === null;
}

/** A6: the React key that gives each route (and the landing) its own designer, so no state leaks across routes. */
export const designerKey = (routeId: string | null): string => `mapping:${routeId ?? 'landing'}`;

/** A save whose request never got an answer (network error): one worded sentence, the mapping is NOT saved. */
export const saveThrownText = (): string => failureText('save the mapping', 0);
