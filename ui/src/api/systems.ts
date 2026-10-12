// T-159 (E5 lane D): the systems registry seam - every call through the ONE typed client
// (src/api/client.ts) against the real API, no mock (E3-F3-S1). Lane B's API (T-102): a system
// answers {id, code, name, direction, type, settings}; a refused save answers 400 with EVERY
// validation error listed in `errors` (and joined in `error`); 404 is the tenant-isolation answer.

import { api } from './client';
import type { ApiAnswer } from './flows';

/** An answer whose refusal also carries every listed error (systems `errors`, contract import `parserErrors`). */
export type ListedAnswer<T> = ApiAnswer<T> & { errors?: string[] };

/** flows.ts's answer() pattern, keeping the multi-error bodies lanes A and B define. */
export function listedAnswer<T>(data: T | undefined, error: unknown, response: Response): ListedAnswer<T> {
  if (response.ok && data !== undefined) return { ok: true, data };
  const body = (error ?? {}) as { error?: string; refusal?: string; errors?: unknown; parserErrors?: unknown };
  const listed = Array.isArray(body.errors) ? body.errors : Array.isArray(body.parserErrors) ? body.parserErrors : null;
  const errors = listed ? listed.map(String) : body.error ? [body.error] : [];
  return { ok: false, status: response.status, error: body.error, refusal: body.refusal, errors };
}

/** The closed register of system types (SystemSettings.TYPES), with each type's settings fields. */
export const SYSTEM_TYPES = ['API', 'DB', 'MQ', 'FILE'] as const;
export type SystemType = (typeof SYSTEM_TYPES)[number];

export type SettingKind = 'text' | 'number' | 'boolean';
export type SettingField = { name: string; label: string; kind: SettingKind; required: boolean };

export const SETTINGS_BY_TYPE: Record<SystemType, SettingField[]> = {
  API: [
    { name: 'baseUrl', label: 'Base address', kind: 'text', required: true },
    { name: 'timeoutMs', label: 'Timeout (ms)', kind: 'number', required: false },
    { name: 'followRedirects', label: 'Follow redirects', kind: 'boolean', required: false }
  ],
  DB: [
    { name: 'host', label: 'Host', kind: 'text', required: true },
    { name: 'port', label: 'Port', kind: 'number', required: true },
    { name: 'database', label: 'Database', kind: 'text', required: true }
  ],
  MQ: [
    { name: 'host', label: 'Host', kind: 'text', required: true },
    { name: 'port', label: 'Port', kind: 'number', required: true },
    { name: 'queue', label: 'Queue', kind: 'text', required: true }
  ],
  FILE: [{ name: 'rootPath', label: 'Root path', kind: 'text', required: true }]
};

export const TYPE_LABELS: Record<SystemType, string> = { API: 'API', DB: 'DB', MQ: 'MQ', FILE: 'File' };

export function settingsFieldsOf(type: string): SettingField[] {
  return (SETTINGS_BY_TYPE as Record<string, SettingField[] | undefined>)[type.trim()] ?? [];
}

export type SystemSettingsValue = Record<string, string | number | boolean>;

export type SystemView = {
  id: string;
  code: string;
  name: string;
  direction: 'INBOUND' | 'OUTBOUND';
  type: string | null;
  settings: SystemSettingsValue | null;
};

export type SystemRegisterBody = {
  code: string;
  name: string;
  direction: 'INBOUND' | 'OUTBOUND';
  type: string | null;
  settings: SystemSettingsValue | null;
};

export type SystemEditBody = { name: string; type: string | null; settings: SystemSettingsValue | null };

export async function listSystems(): Promise<ListedAnswer<SystemView[]>> {
  const { data, error, response } = await api.GET('/api/v1/admin/systems');
  return listedAnswer((data ?? []) as unknown as SystemView[], error, response);
}

export async function readSystem(systemId: string): Promise<ListedAnswer<SystemView>> {
  const { data, error, response } = await api.GET('/api/v1/admin/systems/{systemId}', {
    params: { path: { systemId } }
  });
  return listedAnswer(data as unknown as SystemView, error, response);
}

export async function registerSystem(body: SystemRegisterBody): Promise<ListedAnswer<SystemView>> {
  const { data, error, response } = await api.POST('/api/v1/admin/systems', { body: body as never });
  return listedAnswer(data as unknown as SystemView, error, response);
}

export async function updateSystem(systemId: string, body: SystemEditBody): Promise<ListedAnswer<SystemView>> {
  const { data, error, response } = await api.PUT('/api/v1/admin/systems/{systemId}', {
    params: { path: { systemId } },
    body: body as never
  });
  return listedAnswer(data as unknown as SystemView, error, response);
}

/** The test-connection answer's fixed shape (T-104): every key always present, null where it does not apply. */
export type TestConnectionResult = {
  systemId: string;
  type: string | null;
  result: string;
  httpStatus: number | null;
  responseTimeMs: number | null;
  reason: string | null;
  detail: string | null;
  message: string | null;
};

/** POST /api/v1/admin/systems/{systemId}/test-connection (T-104): test a system's connection - the
 * typed client already carries the operation; a refusal (403/404 and the 429
 * TEST_CONNECTION_RATE_LIMITED) comes back listedAnswer-shaped like every other call here. */
export async function testConnection(systemId: string): Promise<ListedAnswer<TestConnectionResult>> {
  const { data, error, response } = await api.POST('/api/v1/admin/systems/{systemId}/test-connection', {
    params: { path: { systemId } }
  });
  return listedAnswer(data as unknown as TestConnectionResult, error, response);
}

/**
 * The settings object a form sends: only the type's own fields, blanks left out (the API names a
 * missing required one). A whole-number text is sent as a number; anything else is sent as typed,
 * so the API - the one authority - refuses it with its own message.
 */
export function settingsFromForm(type: string, values: Record<string, string | boolean>): SystemSettingsValue {
  const settings: SystemSettingsValue = {};
  for (const field of settingsFieldsOf(type)) {
    const value = values[field.name];
    if (field.kind === 'boolean') {
      if (value === true) settings[field.name] = true;
      continue;
    }
    const text = typeof value === 'string' ? value.trim() : '';
    if (text === '') continue;
    settings[field.name] = field.kind === 'number' && /^-?\d+$/.test(text) ? Number(text) : text;
  }
  return settings;
}

/** The form's values for a stored system's settings. */
export function formFromSettings(settings: SystemSettingsValue | null): Record<string, string | boolean> {
  const values: Record<string, string | boolean> = {};
  for (const [name, value] of Object.entries(settings ?? {})) {
    values[name] = typeof value === 'boolean' ? value : String(value);
  }
  return values;
}
