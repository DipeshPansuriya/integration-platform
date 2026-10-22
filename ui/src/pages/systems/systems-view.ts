// Row 310 lane C (T101): the Systems worklist's pure view logic (10-screens §5) - which drawer is
// open, the row filter, the settings summary, the words of the test-connection row cell and the
// field each validation error points at. No React, no fetch: node --test exercises it directly.

type SystemRow = { code: string; name: string; type?: string | null };

/** The drawer shows the opened system's edit form; otherwise the register form when asked; else it is closed. */
export function drawerMode(openId: string | null, registering: boolean): 'edit' | 'register' | null {
  if (openId) return 'edit';
  return registering ? 'register' : null;
}

/** One text filter over code, name and type, case-insensitive; blank keeps every row. */
export function filterSystems<T extends SystemRow>(systems: T[], text: string): T[] {
  const needle = text.trim().toLowerCase();
  if (!needle) return systems;
  return systems.filter((system) =>
    [system.code, system.name, system.type ?? ''].some((value) => value.toLowerCase().includes(needle))
  );
}

/** The settings as one joined string (the cell truncates it and shows it whole as a tooltip). */
export function settingsSummary(settings: Record<string, unknown> | null | undefined): string {
  if (!settings) return '-';
  return Object.entries(settings)
    .map(([name, value]) => `${name}: ${String(value)}`)
    .join(', ');
}

/** What the row keeps of its last connection test: the endpoint's answer, or the API's refusal. */
export type TestOutcome =
  | { kind: 'answer'; result: string; message: string | null }
  | { kind: 'refusal'; status: number; reason: string };

/** The row cell's badge: the word carries the result, the tone only repeats it (never colour alone). */
export function testCell(outcome: TestOutcome): { tone: 'success' | 'danger' | 'warn'; word: string; detail: string } {
  if (outcome.kind === 'answer') {
    const result = outcome.result.toUpperCase();
    const tone = result === 'SUCCESS' || result === 'OK' ? 'success' : result === 'REFUSED' || result === 'FAILED' || result === 'FAILURE' ? 'danger' : 'warn';
    return { tone, word: outcome.result, detail: outcome.message ?? '' };
  }
  const word = outcome.status === 429 ? 'Too many connection tests, wait a moment' : `Refused (HTTP ${outcome.status})`;
  return { tone: 'danger', word, detail: outcome.reason };
}

/** The form field an API validation error names, as the id its anchor links to; null when it names none. */
export function errorAnchor(error: string): string | null {
  const setting = /^settings\.([A-Za-z0-9_]+)/.exec(error);
  if (setting) return `sys-setting-${setting[1]}`;
  const field = /^(code|name|direction|type)\b/.exec(error);
  return field ? `sys-field-${field[1]}` : null;
}

/** True when one of the API's errors names this settings field (the field then carries aria-invalid). */
export function fieldHasError(errors: string[], settingName: string): boolean {
  return errors.some((error) => errorAnchor(error) === `sys-setting-${settingName}`);
}

/** The word in a row's result cell while its connection test is running (B29 C1). */
export const TESTING_WORD = 'Testing...';

/** A row may start a test only when none is already running for it (parallel clicks hit the 429). */
export function canStartTest(testing: ReadonlySet<string>, id: string): boolean {
  return !testing.has(id);
}
