// T139 (E13, lane e13-t139ui): the delivery-report view's pure view model.
// No DOM, no fetch - unit-tested under node.
import type { DailyCounts } from '../api/reports';

export interface ReportRow {
  key: string;
  interfaceId: string;
  targetId: string;
  successes: number;
  failures: number;
  deliveries: number;
  retriesMade: number;
  retriedThenSucceeded: number;
  stillStuck: number;
}

/** One row per interface/target; failures collapse the three failure classes, deliveries add the successes. */
export function reportRows(answer: readonly DailyCounts[]): ReportRow[] {
  return answer.map((d) => {
    const failures = d.failuresTransient + d.failuresAmbiguous + d.failuresPermanent;
    return {
      key: `${d.interfaceId}/${d.targetId}`,
      interfaceId: d.interfaceId,
      targetId: d.targetId,
      successes: d.successes,
      failures,
      deliveries: d.successes + failures,
      retriesMade: d.retriesMade,
      retriedThenSucceeded: d.retriedThenSucceeded,
      stillStuck: d.stillStuck,
    };
  });
}

export function reportColumns(): string[] {
  return ['Interface', 'Target', 'Successes', 'Failures', 'Deliveries', 'Retries made', 'Retried then succeeded', 'Still stuck'];
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

// A junk value would be a 400, not "no data", so only a well-formed day reaches the client.
export function reportQuery(params: { day?: string }): { day: string } | undefined {
  if (params.day === undefined || !ISO_DATE.test(params.day)) return undefined;
  return { day: params.day };
}

// The backend's days are UTC days.
export function todayUtc(now: Date): string {
  return now.toISOString().slice(0, 10);
}