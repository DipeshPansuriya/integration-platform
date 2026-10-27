import { api } from './client';

export type AllowedHostsResult =
  | { ok: true; hosts: string[] }
  | { ok: false; error: string };

export async function loadAllowedHosts(): Promise<AllowedHostsResult> {
  try {
    const { data, response } = await api.GET('/api/v1/admin/settings/allowed-hosts' as never);
    if (response.ok && data) return { ok: true, hosts: (data as { hosts: string[] }).hosts ?? [] };
    return { ok: false, error: `HTTP ${response.status}` };
  } catch {
    return { ok: false, error: 'Network error' };
  }
}

export async function addAllowedHost(pattern: string): Promise<AllowedHostsResult> {
  try {
    const { data, response } = await api.POST('/api/v1/admin/settings/allowed-hosts' as never, {
      body: { pattern } as never,
    });
    if (response.ok && data) return { ok: true, hosts: (data as { hosts: string[] }).hosts ?? [] };
    const body = (data ?? {}) as { error?: string };
    return { ok: false, error: body.error ?? `HTTP ${response.status}` };
  } catch {
    return { ok: false, error: 'Network error' };
  }
}

export async function removeAllowedHost(pattern: string): Promise<AllowedHostsResult> {
  try {
    const { data, response } = await api.DELETE('/api/v1/admin/settings/allowed-hosts' as never, {
      body: { pattern } as never,
    });
    if (response.ok && data) return { ok: true, hosts: (data as { hosts: string[] }).hosts ?? [] };
    return { ok: false, error: `HTTP ${response.status}` };
  } catch {
    return { ok: false, error: 'Network error' };
  }
}
