// T139 (E13, lane e13-t139ui): fetch the delivery report through the ONE typed client - live, no mock.
// Read LIVE through the ONE typed client, no mock.
import { api } from './client';
import type { ApiAnswer } from './flows';

/** Mirrors the delivery module's DailyCounts record (DeliveryDailyAggregates.java). */
export interface DailyCounts {
  interfaceId: string;
  targetId: string;
  successes: number;
  failuresTransient: number;
  failuresAmbiguous: number;
  failuresPermanent: number;
  retriesMade: number;
  retriedThenSucceeded: number;
  stillStuck: number;
}

function answer<T>(data: T | undefined, error: unknown, response: Response): ApiAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal };
}

export async function deliveryReport(params: { day: string }): Promise<ApiAnswer<DailyCounts[]>> {
  const { data, error, response } = await api.GET('/api/v1/reports/delivery', {
    params: { query: { day: params.day } },
  });
  return answer(data as unknown as DailyCounts[] | undefined, error, response);
}