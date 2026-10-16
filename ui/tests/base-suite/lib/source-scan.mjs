// T130 (row 323, F21 BS-B): static guards over the base suite's own sources and the SQL it reads with.
//
// The dispatch's hard limit: BS-07 / BS-10 / BS-12 never hand-write a pipeline row - message,
// delivery, outbox, stage-timing, attempt, event, payload or audit rows must come from the real
// pipeline. Tenants, users and roles may follow the existing role_grant pattern (boot-backend.mjs:
// 354-373). Every direct read the count oracle makes is ONE read-only statement, and the reading psql
// session is read-only as well (support/db.mjs) - two layers, so a slip in one is still refused.

export const PIPELINE_TABLES = Object.freeze([
  'message', 'message_payload', 'message_stage_timing', 'outbox',
  'delivery', 'delivery_attempt', 'delivery_event', 'audit_event'
]);

// longest name first, then a word boundary: "message" never matches inside "message_stage_timing"
const TABLE = `(?:public\\.)?(${[...PIPELINE_TABLES].sort((a, b) => b.length - a.length).join('|')})\\b`;
const WRITES = [
  `\\binsert\\s+into\\s+${TABLE}`,
  `\\bupdate\\s+(?:only\\s+)?${TABLE}`,
  `\\bdelete\\s+from\\s+(?:only\\s+)?${TABLE}`,
  `\\btruncate\\s+(?:table\\s+)?(?:only\\s+)?${TABLE}`,
  `\\bmerge\\s+into\\s+${TABLE}`,
  `\\bcopy\\s+${TABLE}`
].map((source) => new RegExp(source, 'gi'));

/** Every statement in `text` that writes a pipeline table: [{ line, table, statement }], in text order. */
export function pipelineWrites(text) {
  const hits = [];
  for (const pattern of WRITES) {
    for (const match of String(text).matchAll(pattern)) {
      hits.push({ index: match.index, line: String(text).slice(0, match.index).split('\n').length, table: match[1].toLowerCase(), statement: match[0] });
    }
  }
  return hits.sort((a, b) => a.index - b.index).map(({ line, table, statement }) => ({ line, table, statement }));
}

const LITERALS = [/'(?:[^']|'')*'/g, /"(?:[^"]|"")*"/g, /\$([A-Za-z_]*)\$[\s\S]*?\$\1\$/g, /--[^\n]*/g, /\/\*[\s\S]*?\*\//g];
const NOT_A_READ = /\b(insert|update|delete|merge|truncate|copy|alter|drop|create|grant|revoke|call|do|set|reset|lock|vacuum|analyze|cluster|reindex|refresh|listen|notify|prepare|execute|begin|commit|rollback|savepoint|discard|into)\b/i;

/** True only for ONE plain read: a SELECT (or WITH ... SELECT) with no write, lock or second statement. */
export function isReadOnlySelect(sql) {
  let text = String(sql ?? '');
  for (const literal of LITERALS) text = text.replace(literal, "''");
  text = text.trim().replace(/;\s*$/, '');
  if (text === '' || text.includes(';')) return false;
  if (!/^(select|with)\b/i.test(text)) return false;
  return !NOT_A_READ.test(text);
}

/** IPv4 literals in `text` (dotted quads only; version numbers like 3.13.1 are not addresses). */
export function ipLiterals(text) {
  return [...String(text).matchAll(/(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])/g)].map((match) => match[0]);
}
