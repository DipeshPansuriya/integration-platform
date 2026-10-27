// T-160 (E5 lane D): the contract version seam - every call through the ONE typed client
// (src/api/client.ts) against the real API, no mock (E3-F3-S1). Lane A's API (T-105): import a
// version from an OpenAPI document or a JSON Schema (400 with the parser's errors, nothing
// stored), list contracts with their versions side by side, read one version with its schema,
// submit with a change note, approve (the author may approve their own version; 409 {refusal: NOT_PENDING_APPROVAL} for a version not submitted).

import { api } from './client';
import { HARNESS_LOGIN_HEADER } from './flows';
import type { components } from './openapi.d';
import { listedAnswer, type ListedAnswer } from './systems';

export type ContractRole = NonNullable<components['schemas']['ContractBody']['role']>;
export const CONTRACT_ROLES: ContractRole[] = ['SOURCE_REQUEST', 'SOURCE_RESPONSE', 'TARGET_REQUEST', 'TARGET_RESPONSE'];
export type ContractFormat = 'OPENAPI' | 'JSON_SCHEMA';

export type Contract = components['schemas']['ContractBody'];
export type ContractVersionSummary = components['schemas']['ContractVersionSummary'];
export type ContractVersion = components['schemas']['ContractVersionBody'];

export type ImportBody = {
  system: string;
  role: ContractRole;
  versionLabel: string;
  format: ContractFormat;
  /** The document as typed: OpenAPI as JSON or YAML text, a JSON Schema as JSON text (the API parses it). */
  document: string;
  schemaName?: string;
};

export async function listContracts(): Promise<ListedAnswer<Contract[]>> {
  const { data, error, response } = await api.GET('/api/v1/admin/contracts');
  return listedAnswer(data?.contracts ?? (data ? [] : undefined), error, response);
}

export async function readContractVersion(versionId: string): Promise<ListedAnswer<ContractVersion>> {
  const { data, error, response } = await api.GET('/api/v1/admin/contracts/versions/{versionId}', {
    params: { path: { versionId } }
  });
  return listedAnswer(data, error, response);
}

export async function importContractVersion(body: ImportBody): Promise<ListedAnswer<ContractVersion>> {
  const { data, error, response } = await api.POST('/api/v1/admin/contracts/versions/import', { body: body as never });
  return listedAnswer(data, error, response);
}

export async function submitContractVersion(versionId: string, changeNote: string): Promise<ListedAnswer<unknown>> {
  const { data, error, response } = await api.POST('/api/v1/admin/contracts/versions/{versionId}/submit', {
    params: { path: { versionId } },
    body: { changeNote } as never
  });
  return listedAnswer(data, error, response);
}

export async function approveContractVersion(versionId: string, asLogin?: string): Promise<ListedAnswer<ContractVersion>> {
  const { data, error, response } = await api.POST('/api/v1/admin/contracts/versions/{versionId}/approve', {
    params: { path: { versionId } },
    headers: asLogin ? { [HARNESS_LOGIN_HEADER]: asLogin } : undefined
  });
  return listedAnswer(data, error, response);
}

/** The top-level field names of a stored JSON Schema (the side-by-side view lists them). */
export function schemaFields(schema: ContractVersion['jsonSchema']): { name: string; type: string; required: boolean }[] {
  const properties = (schema?.properties ?? {}) as Record<string, { type?: unknown }>;
  const required = new Set(Array.isArray(schema?.required) ? (schema.required as unknown[]).map(String) : []);
  return Object.entries(properties).map(([name, property]) => ({
    name,
    type: typeof property?.type === 'string' ? property.type : Array.isArray(property?.type) ? property.type.join(' | ') : '-',
    required: required.has(name)
  }));
}
