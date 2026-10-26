// Row 372 M9: decodeURIComponent throws URIError on a malformed escape ("%E0%A4%A"), and the router runs it
// while reading the address - one bad link would blank the whole shell. A bad escape keeps the raw text instead
// (the screen then shows its own "not found" for that id).
export function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
