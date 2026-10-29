// Console wiring helpers: small, DOM-only utilities the src/console/pages containers share.
// No fetch here - every container reads through src/api/* (or src/api/hooks.ts) itself.
import './console-pages.css';

/** Moves the hash router to `href` (the console's routes are all `#/...`). */
export function goTo(href: string): void {
  if (typeof window !== 'undefined') window.location.hash = href;
}

const csvCell = (value: string | number): string => {
  // CSV/formula injection: a text cell opening with = + - @ (or tab / CR) would run as a spreadsheet formula,
  // so it is prefixed with ' and read as text. Numbers stay numbers.
  const raw = String(value);
  const text = typeof value === 'string' && /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
};

/** Saves the rows already on screen as a CSV file (client side; no export endpoint exists). */
export function downloadCsv(filename: string, header: readonly string[], rows: readonly (readonly (string | number)[])[]): void {
  if (typeof document === 'undefined' || typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') return;
  const body = [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob([body], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
