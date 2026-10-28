// Row 575 S4 (spec 4.4, plan resilience note): the "no message yet" check after a flow is published. A UI read of an internal API:
// every check is one call with a 10 s timeout (a timeout counts as an error), the next tick is scheduled only after the answer
// (no retry storm), and it stops on arrival, after 10 minutes, after 3 failures in a row and while the tab is hidden. Only a
// hidden stop resumes by itself; every other stop stays stopped until the user asks again. Timers are injected so it is unit-tested
// with a fake clock.

export const POLL_INTERVAL_MS = 15000;
export const POLL_MAX_MS = 600000;
export const POLL_MAX_ERRORS = 3;
export const POLL_CHECK_TIMEOUT_MS = 10000;

export type PollCheck = 'arrived' | 'none' | 'error';
export type PollState = 'waiting' | 'arrived' | 'stopped-time' | 'stopped-errors' | 'stopped-hidden';

export function createFirstMessagePoll(opts: {
  check: () => Promise<PollCheck>;
  onState: (state: PollState) => void;
  now: () => number;
  setTimer: (fn: () => void | Promise<void>, ms: number) => unknown;
  clearTimer: (id: unknown) => void;
  isHidden: () => boolean;
}): { start: (options?: { immediate?: boolean }) => void; stop: () => void; resumeFromHidden: () => void } {
  let timer: unknown = null;
  let checkTimer: unknown = null;
  let run = 0; // a stop() or start() makes an in-flight check's answer stale
  let startedAt = 0;
  let errors = 0;
  let state: PollState | null = null;

  const emit = (next: PollState) => {
    state = next;
    opts.onState(next);
  };

  const cancel = () => {
    if (timer !== null) opts.clearTimer(timer);
    if (checkTimer !== null) opts.clearTimer(checkTimer);
    timer = null;
    checkTimer = null;
  };

  /** One check, raced against the per-call timeout; a rejection or a timeout is an error. */
  const timedCheck = (): Promise<PollCheck> =>
    new Promise<PollCheck>((resolve) => {
      checkTimer = opts.setTimer(() => resolve('error'), POLL_CHECK_TIMEOUT_MS);
      opts.check().then(resolve, () => resolve('error'));
    }).then((answer) => {
      if (checkTimer !== null) opts.clearTimer(checkTimer);
      checkTimer = null;
      return answer;
    });

  const tick = async (mine: number): Promise<void> => {
    timer = null;
    if (mine !== run) return;
    if (opts.isHidden()) return void emit('stopped-hidden');
    if (opts.now() - startedAt >= POLL_MAX_MS) return void emit('stopped-time');
    const answer = await timedCheck();
    if (mine !== run) return;
    if (answer === 'arrived') return void emit('arrived');
    errors = answer === 'error' ? errors + 1 : 0;
    if (errors >= POLL_MAX_ERRORS) return void emit('stopped-errors');
    timer = opts.setTimer(() => tick(mine), POLL_INTERVAL_MS);
  };

  const start = (options?: { immediate?: boolean }) => {
    cancel();
    run += 1;
    startedAt = opts.now();
    errors = 0;
    emit('waiting');
    const mine = run;
    if (options?.immediate) void tick(mine);
    else timer = opts.setTimer(() => tick(mine), POLL_INTERVAL_MS);
  };

  return {
    start,
    stop() {
      run += 1;
      cancel();
    },
    /** The tab became visible: resume ONLY from a hidden stop (arrived / time / errors stay as they are). */
    resumeFromHidden() {
      if (state === 'stopped-hidden') start({ immediate: true });
    }
  };
}
