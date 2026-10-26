// B29 C1/C2: what a person reads when the API refuses a read - plain words, never "(HTTP 500)" or an
// internal token. The screens keep the code in a data-refusal attribute for tests and support.

/** `subject` is the thing that could not be read, e.g. "the systems list". */
export function refusalWords(status: number, subject: string): string {
  if (status === 401) return `Your session has ended, so ${subject} could not be loaded. Sign in again, then retry.`;
  if (status === 403) return `You do not have permission to see ${subject}. Ask an administrator for access.`;
  if (status === 404) return `${subject.charAt(0).toUpperCase()}${subject.slice(1)} was not found.`;
  if (status === 429) return `Too many requests just now, so ${subject} could not be loaded. Wait a moment, then try again.`;
  if (status >= 500) return `The server could not load ${subject}. Try again in a moment; if it keeps failing, contact support.`;
  return `${subject.charAt(0).toUpperCase()}${subject.slice(1)} could not be loaded. Try again.`;
}
