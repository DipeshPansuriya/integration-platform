// T-022 test protocol (E3 lane D): the console reads which HARNESS bindings the running
// system exposes from /console-config.json - a file the screen-test build writes, absent in
// production (the fetch 404s, the extra affordances never render). It names logins only;
// the backend still authorizes every call for real (no token ever reaches the browser).

export type HarnessConfig = { logins?: string[] };

let config: HarnessConfig | null = null;

export async function loadHarnessConfig(): Promise<void> {
  try {
    const response = await fetch('/console-config.json');
    config = response.ok ? ((await response.json()) as HarnessConfig) : null;
  } catch {
    config = null;
  }
}

export function hasHarnessLogin(name: string): boolean {
  return config?.logins?.includes(name) ?? false;
}
