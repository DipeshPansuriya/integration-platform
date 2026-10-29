// Lane A - pure view-model for the Failures screen (row-582 parity: the Action column is
// entirely hidden when the operator cannot operate, not just greyed out).
export interface FailureRow {
  id: string;
  code: string;
  message: string;
  flow: string;
  time: string;
  retriesUsed: number;
  retriesMax: number;
}

export function unresolvedCount(rows: FailureRow[]): number {
  return rows.length;
}

export function isExhausted(row: FailureRow): boolean {
  return row.retriesUsed >= row.retriesMax;
}

export function retriesLabel(row: FailureRow): string {
  return `${row.retriesUsed}/${row.retriesMax}`;
}

export function failuresSubtitle(count: number): string {
  if (count === 0) return 'No unresolved failures';
  return count === 1 ? '1 unresolved failure' : `${count} unresolved failures`;
}