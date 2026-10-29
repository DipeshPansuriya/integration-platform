import { consoleFetch } from './client';

export type HostEntry = { id: string; pattern: string };

const BASE = '/api/v1/admin/destinations';

export async function listAllowedHosts(): Promise<HostEntry[]> {
  const res = await consoleFetch(BASE);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

export async function addAllowedHost(pattern: string): Promise<HostEntry> {
  const res = await consoleFetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pattern }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || `${res.status}`);
  }
  return res.json();
}

export async function removeAllowedHost(id: string): Promise<void> {
  const res = await consoleFetch(`${BASE}/${id}`, { method: 'DELETE' });
  if (!res.ok && res.status !== 204) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
}
