// Row 575 S4 (spec 4.4, plan resilience note): "waiting for the first message" after a flow is published. A UI read of an internal
// API: every check is one call, the next tick is scheduled only after the answer (no retry storm), it stops on arrival, after
// 10 minutes, after 3 failures in a row and while the tab is hidden. Timers are injected so it is unit-tested with a fake clock.

export const POLL_INTERVAL_MS = 15000;
export const POLL_MAX_MS = 600000;
export const POLL_MAX_ERRORS = 3;

export type PollCheck = 'arrived' | 'none' | 'error';
export type PollState = 'waiting' | 'arrived' | 'stopped-time' | 'stopped-errors' | 'stopped-hidden';

export function createFirstMessagePoll(opts: {
  check: () => Promise<PollCheck>;
  onState: (state: PollState) => void;
  now: () => number;
  setTimer: (fn: () => void | Promise<void>, ms: number) => unknown;
  clearTimer: (id: unknown) => void;
  isHidden: () => boolean;
}): { start: (options?: { immediate?: boolean }) => void; stop: () => void } {
  let timer: unknown = null;
  let run = 0; // a stop() or start() makes an in-flight check's answer stale
  let startedAt = 0;
  let errors = 0;

  const cancel = () => {
    if (timer !== null) opts.clearTimer(timer);
    timer = null;
  };

  const tick = async (mine: number): Promise<void> => {
    timer = null;
    if (mine !== run) return;
    if (opts.isHidden()) return void opts.onState('stopped-hidden');
    if (opts.now() - startedAt >= POLL_MAX_MS) return void opts.onState('stopped-time');
    const answer = await opts.check().catch((): PollCheck => 'error');
    if (mine !== run) return;
    if (answer === 'arrived') return void opts.onState('arrived');
    errors = answer === 'error' ? errors + 1 : 0;
    if (errors >= POLL_MAX_ERRORS) return void opts.onState('stopped-errors');
    timer = opts.setTimer(() => tick(mine), POLL_INTERVAL_MS);
  };

  return {
    start(options) {
      cancel();
      run += 1;
      startedAt = opts.now();
      errors = 0;
      opts.onState('waiting');
      const mine = run;
      if (options?.immediate) void tick(mine);
      else timer = opts.setTimer(() => tick(mine), POLL_INTERVAL_MS);
    },
    stop() {
      run += 1;
      cancel();
    }
  };
}
