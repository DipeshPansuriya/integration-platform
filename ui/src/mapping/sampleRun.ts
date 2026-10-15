// T92 (row 278 close-E7, T-162 gap 1): the mapping designer's "Run sample" logic. A sample runs the
// SAVED mapping through POST /api/v1/admin/mappings/{id}/test on the one typed client - the endpoint
// runs the stored expression, never the unsaved drawing, so an unsaved drawing is refused here
// before any call. Nothing transforms in the browser. Every refusal is returned for the screen to
// show (tests/mapping/sample-run.test.mjs).

import type { ConsoleClient } from '../api/client';

export const NOT_JSON = 'The sample is not valid JSON - nothing was run';
export const SAVE_FIRST = 'Save the mapping first - the sample runs the saved mapping';

/** ok: the engine answered OK · error: ERROR / MAPPING_TIMEOUT or no answer · refused: nothing ran. */
export type SampleResult = { state: 'ok' | 'error' | 'refused'; status: string | null; text: string };

type TestAnswer = { mappingId?: string; status?: string; output?: unknown; error?: string | null };
type ErrorBody = { error?: string; refusal?: string; message?: string; code?: string } | undefined;

/** The server's own words for a refusal: the platform's {error}/{refusal} or the documented {message, code}. */
export function refusalMessage(body: unknown, status: number): string {
  const b = body as ErrorBody;
  return b?.error ?? b?.message ?? b?.refusal ?? b?.code ?? `HTTP ${status}`;
}

export async function runMappingSample(
  client: Pick<ConsoleClient, 'POST'>,
  mappingId: string | null,
  inputText: string,
  dirty: boolean
): Promise<SampleResult> {
  let sample: unknown;
  try {
    sample = JSON.parse(inputText);
  } catch {
    return { state: 'refused', status: null, text: NOT_JSON };
  }
  if (dirty || !mappingId) return { state: 'refused', status: null, text: SAVE_FIRST };
  try {
    const { data, error, response } = await client.POST('/api/v1/admin/mappings/{id}/test', {
      params: { path: { id: mappingId } },
      body: { sample } as never
    });
    if (error !== undefined || data === undefined) {
      return { state: 'refused', status: null, text: `Refused (${response.status}): ${refusalMessage(error, response.status)}` };
    }
    const answer = data as TestAnswer;
    const status = answer.status ?? 'ERROR';
    if (status === 'OK') return { state: 'ok', status, text: JSON.stringify(answer.output ?? null, null, 2) };
    return { state: 'error', status, text: answer.error ?? 'the engine gave no error text' };
  } catch (e) {
    return { state: 'error', status: null, text: `The sample could not run: ${(e as Error).message}` };
  }
}
