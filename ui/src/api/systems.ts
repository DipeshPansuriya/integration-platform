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
export const SYSTEM_TYPES = ['API', 'DB', 'MQ', 'FILE', 'KAFKA'] as const;
export type SystemType = (typeof SYSTEM_TYPES)[number];

/** 'list' = a comma-separated list on screen, an array on the wire (no field uses it today: the DB approved tables moved out of the settings, E16 F3). */
export type SettingKind = 'text' | 'number' | 'boolean' | 'list';
export type SettingField = { name: string; label: string; kind: SettingKind; required: boolean; hint?: string };

export const SETTINGS_BY_TYPE: Record<SystemType, SettingField[]> = {
  API: [
    { name: 'baseUrl', label: 'Base address', kind: 'text', required: true },
    { name: 'timeoutMs', label: 'Timeout (ms)', kind: 'number', required: false },
    { name: 'followRedirects', label: 'Follow redirects', kind: 'boolean', required: false }
  ],
  DB: [
    { name: 'host', label: 'Host', kind: 'text', required: true },
    { name: 'port', label: 'Port', kind: 'number', required: true },
    { name: 'database', label: 'Database', kind: 'text', required: true },
    { name: 'user', label: 'Account name', kind: 'text', required: false, hint: 'A plain identifier. The password is a credential, stored separately.' },
    { name: 'table', label: 'Table', kind: 'text', required: false, hint: 'A second person must approve the table (approved-tables API) before the first send.' }
  ],
  MQ: [
    { name: 'host', label: 'Host', kind: 'text', required: true },
    { name: 'port', label: 'Port', kind: 'number', required: true },
    { name: 'queue', label: 'Queue', kind: 'text', required: true }
  ],
  FILE: [{ name: 'rootPath', label: 'Root path', kind: 'text', required: true }],
  KAFKA: [
    { name: 'bootstrapServers', label: 'Bootstrap servers', kind: 'text', required: true, hint: 'Comma-separated host:port list, for example broker1:9092,broker2:9092.' },
    { name: 'topic', label: 'Topic', kind: 'text', required: true, hint: "A tenant topic name. The platform's own topics are refused." }
  ]
};

export const TYPE_LABELS: Record<SystemType, string> = { API: 'API', DB: 'DB', MQ: 'MQ', FILE: 'File', KAFKA: 'Kafka' };

/** A line of guidance shown under a type's settings; Kafka's credential is a reference, never typed into this form. */
const NOTES_BY_TYPE: Partial<Record<SystemType, string>> = {
  KAFKA: 'The SASL or TLS password is a credential of type KAFKA_TOPIC, stored separately. It is never entered here.'
};

export function settingsNoteOf(type: string): string | undefined {
  return (NOTES_BY_TYPE as Record<string, string | undefined>)[type.trim()];
}

export function settingsFieldsOf(type: string): SettingField[] {
  return (SETTINGS_BY_TYPE as Record<string, SettingField[] | undefined>)[type.trim()] ?? [];
}

export type SystemSettingsValue = Record<string, string | number | boolean | string[]>;

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
    if (field.kind === 'list') {
      const entries = text.split(',').map((entry) => entry.trim()).filter((entry) => entry !== '');
      if (entries.length > 0) settings[field.name] = entries;
      continue;
    }
    settings[field.name] = field.kind === 'number' && /^-?\d+$/.test(text) ? Number(text) : text;
  }
  return settings;
}

/** How a stored value reads on screen (the one conversion formFromSettings and the no-change check share). */
function shown(value: unknown): string {
  return Array.isArray(value) ? value.join(', ') : String(value);
}

/** The form's values for a stored system's settings. */
export function formFromSettings(settings: SystemSettingsValue | null): Record<string, string | boolean> {
  const values: Record<string, string | boolean> = {};
  for (const [name, value] of Object.entries(settings ?? {})) {
    values[name] = typeof value === 'boolean' ? value : shown(value);
  }
  return values;
}

/** Stored settings still apply to the form only while the type is the one they were saved under. */
function storedFor(type: string, stored: SystemSettingsValue | null | undefined, storedType: string | null | undefined) {
  return stored && storedType != null && storedType.trim() === type.trim() ? stored : null;
}

/**
 * The settings an EDIT sends: the stored object with the form's own fields merged over it. Every key the
 * form does not own (Kafka securityProtocol, DB sslmode, halfOpenTrialCalls, ...) goes back untouched, so an
 * unchanged save is a no-op on the wire; an owned key goes only when the user cleared it. A value the user did
 * not change is sent as stored, not re-typed. When the type changed, the old type's extra keys are dropped.
 */
export function settingsForEdit(
  type: string,
  values: Record<string, string | boolean>,
  stored: SystemSettingsValue | null | undefined,
  storedType: string | null | undefined
): SystemSettingsValue {
  const fromForm = settingsFromForm(type, values);
  const before = storedFor(type, stored, storedType);
  if (!before) return fromForm;
  const owned = new Set(settingsFieldsOf(type).map((field) => field.name));
  const settings: SystemSettingsValue = {};
  for (const [name, value] of Object.entries(before)) if (!owned.has(name)) settings[name] = value;
  for (const field of settingsFieldsOf(type)) {
    const name = field.name;
    const old = before[name];
    const next = fromForm[name];
    if (next === undefined) {
      // an unticked box over a stored explicit false is no change
      if (field.kind === 'boolean' && old === false) settings[name] = false;
      continue;
    }
    settings[name] = old !== undefined && typeof old !== 'boolean' && shown(old) === shown(next) ? old : next;
  }
  return settings;
}

const CREDENTIAL_NAME = /password|passwd|secret|token|credential|apikey|api_key|private/i;

/** The stored keys the form does not own, for a read-only look (never editable here); credential-looking names are masked. */
export function extraSettingsOf(
  type: string,
  stored: SystemSettingsValue | null | undefined,
  storedType: string | null | undefined
): { name: string; value: string }[] {
  const before = storedFor(type, stored, storedType);
  if (!before) return [];
  const owned = new Set(settingsFieldsOf(type).map((field) => field.name));
  return Object.entries(before)
    .filter(([name]) => !owned.has(name))
    .map(([name, value]) => ({
      name,
      value: CREDENTIAL_NAME.test(name) ? '(hidden)' : typeof value === 'object' && !Array.isArray(value) ? JSON.stringify(value) : shown(value)
    }));
}

const BOOTSTRAP_WORDS = 'settings.bootstrapServers must be a comma-separated list of host:port, port 1 to 65535';

/** Mirrors SystemSettings.bootstrapServersProblem: every entry is host:port, no blanks, '/' or '@' in the host, port 1-65535. */
function bootstrapServersOk(text: string): boolean {
  if (text.length > 2048) return false;
  return text.split(',').every((server) => {
    const entry = server.trim();
    const at = entry.lastIndexOf(':');
    if (at <= 0) return false;
    const host = entry.slice(0, at);
    if (host.trim() === '' || /[\s/@]/.test(host)) return false;
    const port = entry.slice(at + 1);
    return /^[+-]?\d+$/.test(port) && Number(port) >= 1 && Number(port) <= 65535;
  });
}

/**
 * The errors the client can name before a save, in the server's own words. Only the KAFKA shape is
 * mirrored (required fields and the bootstrap-servers list); the platform-topic refusal and every other
 * type's rules stay the server's - the API is the one authority, its message shown as-is.
 */
export function clientSettingsErrors(type: string, values: Record<string, string | boolean>): string[] {
  if (type.trim() !== 'KAFKA') return [];
  const settings = settingsFromForm('KAFKA', values);
  const errors: string[] = [];
  const servers = settings.bootstrapServers;
  if (servers === undefined) errors.push('settings.bootstrapServers is required for type KAFKA');
  else if (typeof servers !== 'string' || !bootstrapServersOk(servers)) errors.push(BOOTSTRAP_WORDS);
  if (settings.topic === undefined) errors.push('settings.topic is required for type KAFKA');
  return errors;
}
