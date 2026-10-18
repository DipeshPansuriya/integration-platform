/** Operator actions on one delivery: replay (D7) and retry. Own module so the screen hook harness, which stubs ../api/messages, does not hide these exports. */
import { api } from './client';

export type OperatorResult = { outcome: string; deliveryId: string; state: string | null };
export type OperatorAnswer =
  | { ok: true; data: OperatorResult }
  | { ok: false; status: number; error?: string; refusal?: string; state?: string };

function operatorAnswer(data: unknown, error: unknown, response: Response): OperatorAnswer {
  if (response.ok && data !== undefined) return { ok: true, data: data as OperatorResult };
  const body = (error ?? {}) as { error?: string; refusal?: string; state?: string };
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal, state: body.state };
}

export async function replayDelivery(deliveryId: string): Promise<OperatorAnswer> {
  const { data, error, response } = await api.POST('/api/v1/admin/messages/deliveries/{deliveryId}/replay', {
    params: { path: { deliveryId } }
  });
  return operatorAnswer(data, error, response);
}

export async function retryDelivery(deliveryId: string): Promise<OperatorAnswer> {
  const { data, error, response } = await api.POST('/api/v1/admin/messages/deliveries/{deliveryId}/retry', {
    params: { path: { deliveryId } }
  });
  return operatorAnswer(data, error, response);
}
