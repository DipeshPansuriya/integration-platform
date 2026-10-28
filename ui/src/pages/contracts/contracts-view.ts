// Row 310 lane C (T101): the Contracts screen's pure view logic (10-screens §6) - the grouped list
// (one card per system/role), the worded status badge and the "diff" hint between two compared
// panes. No React, no fetch: node --test exercises it directly.

import { statusWord, type Tone } from '../../ui/status-words';

type ContractLike = { system?: string | null; role?: string | null };
type FieldLike = { name: string; type: string; required: boolean };

/** The card key of a contract: its system and role, the structure the data already has. */
export const contractKey = (contract: ContractLike) => `${contract.system ?? ''}/${contract.role ?? ''}`;

/** One card per system/role, sorted by system then role; each contract's versions keep the API's order. */
export function contractGroups<T extends ContractLike>(contracts: T[]): { key: string; contract: T }[] {
  return contracts
    .map((contract) => ({ key: contractKey(contract), contract }))
    .sort((a, b) => a.key.localeCompare(b.key));
}

/** The status badge: the word is the carrier, the tone only repeats it (one table: ui/status-words.ts); an unknown status is worded, neutral. */
export function statusBadge(status: string | null | undefined): { tone: Tone; word: string } {
  return statusWord('contractVersion', status);
}

/** Fields of one pane that differ from the other pane: 'changed' (type or required differ) or 'only-here'. */
export function fieldDiff(mine: FieldLike[], other: FieldLike[] | undefined): Record<string, 'changed' | 'only-here'> {
  if (!other) return {};
  const theirs = new Map(other.map((field) => [field.name, field]));
  const diff: Record<string, 'changed' | 'only-here'> = {};
  for (const field of mine) {
    const match = theirs.get(field.name);
    if (!match) diff[field.name] = 'only-here';
    else if (match.type !== field.type || match.required !== field.required) diff[field.name] = 'changed';
  }
  return diff;
}

/** A row action's accessible name carries the contract/version key, so every row's button differs (B29 C2). */
export const rowActionLabel = (verb: string, key: string) => `${verb} ${key}`;

/** The role as words (one table: ui/status-words.ts); an unknown role is worded, not raw (B29 C6). */
export const roleWord = (role: string) => statusWord('role', role).word;
