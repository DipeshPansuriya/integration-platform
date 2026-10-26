// T131 (row 323, F21 BS-C, BS-08): the timeline oracle over the T110 answer (lane t112
// ui/src/api/messages.ts Timeline) and the T112 detail screen's rows (MessageDetail.tsx data-stage-row /
// data-stage-status / data-duration-ms).
//
// The stage names are main's closed set (V2_1_1110__message_stage_timing.sql:31; StageTimings.java:29-38).
// Plan FLAG: T105's criteria name ROUTED / TRANSFORMED / DELIVERED / FAILED, which the CHECK refuses - a
// timeline carrying those names is reported as such, never mapped onto the landed set.

export const STAGES = Object.freeze(['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);

const ms = (iso) => (typeof iso === 'string' ? Date.parse(iso) : Number.NaN);

/** Problems of one complete (successful) journey's timeline; [] when every stage is there, in order, timed. */
export function timelineProblems(timeline, trackingId) {
  if (!timeline || !Array.isArray(timeline.stages)) return ['no timeline answer'];
  const problems = [];
  if (timeline.trackingId !== trackingId) problems.push(`tracking id ${timeline.trackingId}, not ${trackingId}`);
  const names = timeline.stages.map((s) => s.stage);
  for (const name of new Set(names)) {
    if (!STAGES.includes(name)) problems.push(`stage ${name} is not in the landed set ${STAGES.join(', ')}`);
    const count = names.filter((n) => n === name).length;
    if (count > 1) problems.push(`stage ${name} appears ${count} times`);
  }
  for (const name of STAGES) if (!names.includes(name)) problems.push(`stage ${name} is missing`);

  const known = timeline.stages.filter((s) => STAGES.includes(s.stage));
  const order = known.map((s) => STAGES.indexOf(s.stage));
  if (order.some((index, i) => i > 0 && index < order[i - 1])) problems.push(`stages out of pipeline order: ${known.map((s) => s.stage).join(', ')}`);

  let previous = null;
  for (const s of [...known].sort((a, b) => STAGES.indexOf(a.stage) - STAGES.indexOf(b.stage))) {
    const start = ms(s.startedAt);
    const end = ms(s.finishedAt);
    if (Number.isNaN(start)) problems.push(`${s.stage} has no start time`);
    if (previous && !Number.isNaN(start) && start < ms(previous.startedAt)) problems.push(`${s.stage} starts before ${previous.stage}`);
    if (s.finishedAt === null || s.finishedAt === undefined) {
      problems.push(`${s.stage} has not finished`);
    } else if (!Number.isNaN(start) && !Number.isNaN(end)) {
      if (end < start) problems.push(`${s.stage} finishes before it starts`);
      else if (typeof s.durationMs === 'number' && s.durationMs >= 0 && Math.abs(s.durationMs - (end - start)) > 1) {
        problems.push(`${s.stage} duration ${s.durationMs} ms, the timestamps say ${end - start} ms`);
      }
    }
    if (typeof s.durationMs !== 'number' || s.durationMs < 0) problems.push(`${s.stage} duration ${s.durationMs} is not >= 0`);
    previous = s;
  }
  if (typeof timeline.totalMs !== 'number' || timeline.totalMs < 0) problems.push(`total ${timeline.totalMs} is not >= 0`);
  return problems;
}

/** The screen's rows ({ stage, status, durationMs as the data attribute's text }) against the API answer. */
export function screenRowProblems(rows, timeline) {
  const problems = [];
  const apiOrder = timeline.stages.map((s) => s.stage);
  const shown = rows.map((r) => r.stage);
  if (shown.join() !== apiOrder.join()) problems.push(`screen order ${shown.join(', ')}, the API order ${apiOrder.join(', ')}`);
  for (const row of rows) {
    if (row.status !== 'done') problems.push(`${row.stage} shows ${row.status}, not done`);
    const api = timeline.stages.find((s) => s.stage === row.stage);
    const shownMs = row.durationMs === '' || row.durationMs === null ? null : Number(row.durationMs);
    if (api && shownMs !== api.durationMs) problems.push(`${row.stage} shows ${row.durationMs} ms, the API ${api.durationMs} ms`);
  }
  return problems;
}

/**
 * The T105 rows of one message (stage | started | finished-or-'') as the timeline timelineProblems reads.
 * Durations come from the timestamps; an unfinished stage has none (null), and so has the total - the oracle
 * then names both, never a made-up 0.
 */
export function timelineOfRows(trackingId, rows) {
  if (!Array.isArray(rows)) throw new Error('timelineOfRows: rows must be an array of [stage, started, finished]');
  const stages = rows.map((row) => {
    if (!Array.isArray(row) || row.length !== 3) throw new Error(`timelineOfRows: a row has three columns, got ${JSON.stringify(row)}`);
    const [stage, startedAt, rawFinish] = row;
    const finishedAt = rawFinish === '' ? null : rawFinish;
    const span = finishedAt === null ? Number.NaN : ms(finishedAt) - ms(startedAt);
    return { stage, startedAt, finishedAt, durationMs: Number.isNaN(span) ? null : span };
  });
  const starts = stages.map((s) => ms(s.startedAt));
  const ends = stages.map((s) => (s.finishedAt === null ? Number.NaN : ms(s.finishedAt)));
  const totalMs = stages.length === 0 || [...starts, ...ends].some(Number.isNaN) ? null : Math.max(...ends) - Math.min(...starts);
  return { trackingId, stages, totalMs };
}
