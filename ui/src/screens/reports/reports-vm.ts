// Reports screen view-model (lane D). Pure functions/consts only so tests/rules/ts-resolve.mjs can
// strip the types under plain node --test. Interim thresholds while Decision 6 stays open - revisit
// when the delivery-quality rule is settled; keep this file the single place thresholds live.

export interface ReportRow {
  flow: string;
  sent: number;
  delivered: number;
  failed: number;
  successPct: number;
  latencyMs?: number;
}

export type SuccessBand = 'ok' | 'warn' | 'err';

/** Interim success-rate band thresholds (percent of messages delivered). */
export const STALE_BANDS: { ok: number; warn: number; err: number } = { ok: 99.9, warn: 99.8, err: 0 };

export function successBand(pct: number): SuccessBand {
  if (pct >= STALE_BANDS.ok) return 'ok';
  if (pct >= STALE_BANDS.warn) return 'warn';
  return 'err';
}