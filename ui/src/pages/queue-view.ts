// B28 (UI audit B4): the queue dashboard's view state, pure. A refusal never leaves stale rows beside it.
export type QueueStatus = 'loading' | 'refused' | 'empty' | 'ready';

export function queueStatus(s: { loaded: boolean; refusal: string | null; rowCount: number }): QueueStatus {
  if (!s.loaded) return 'loading';
  if (s.refusal !== null) return 'refused';
  return s.rowCount === 0 ? 'empty' : 'ready';
}
