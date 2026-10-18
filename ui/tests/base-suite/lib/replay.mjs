// T131 (row 323, F21 BS-C, BS-10): the replay oracle over rows read directly (direct-sql.mjs, one
// read-only SELECT each) - T111's criteria 1-4 as the scenario sees them.
//
// Registers on main: delivery state V2_3_1:28-29; delivery_event type V2_3_3:14-16 (REPLAY is already in
// it); delivery_attempt is append-only (V2_3_2). A poll can miss the short DUE, so the REPLAY event is
// the record of DEAD_LETTERED -> DUE, and the polled walk may only move forward through DUE / SENDING to
// DELIVERED.

const AFTER_REPLAY = Object.freeze(['DUE', 'SENDING', 'DELIVERED']);

/** Consecutive repeats of one polled state dropped. */
export function stateWalk(samples) {
  return samples.filter((state, i) => i === 0 || state !== samples[i - 1]);
}

/** Problems of the replay's state walk and its new delivery_event types; [] for DEAD_LETTERED -> DUE -> DELIVERED. */
export function replayWalkProblems({ before, walk, events }) {
  const problems = [];
  if (before !== 'DEAD_LETTERED') problems.push(`before the replay ${before}, not DEAD_LETTERED`);
  if (walk.length === 0) problems.push('no state after the replay');
  for (const state of walk) {
    if (!AFTER_REPLAY.includes(state)) problems.push(`after the replay the delivery went ${state} - only ${AFTER_REPLAY.join(' / ')} are allowed`);
  }
  const steps = walk.filter((s) => AFTER_REPLAY.includes(s)).map((s) => AFTER_REPLAY.indexOf(s));
  if (steps.some((step, i) => i > 0 && step < steps[i - 1])) problems.push(`the walk went backwards: ${walk.join(' -> ')}`);
  if (walk.length > 0 && walk[walk.length - 1] !== 'DELIVERED') problems.push(`the walk ends ${walk[walk.length - 1]}, not DELIVERED`);

  if (events[0] !== 'REPLAY') problems.push(`first new event ${events[0] ?? 'none'}, not REPLAY`);
  const replayAt = events.indexOf('REPLAY');
  if (replayAt >= 0) {
    const later = events.slice(replayAt + 1);
    if (!later.includes('DELIVER')) problems.push('no DELIVER event after REPLAY');
    if (later.includes('DEAD_LETTER')) problems.push('a DEAD_LETTER after REPLAY');
  }
  return problems;
}

const ATTEMPT_FIELDS = Object.freeze(['layer', 'outcomeClass', 'startedAt', 'finishedAt', 'detailHash']);

/** Attempt rows { id, layer, outcomeClass, startedAt, finishedAt, detailHash } before and after: the history is kept. */
export function attemptHistoryProblems(before, after) {
  const problems = [];
  if (before.length === 0) problems.push('no attempt before the replay - nothing dead-lettered through a send');
  const afterIds = after.map((a) => a.id);
  for (const old of before) {
    const now = after.find((a) => a.id === old.id);
    if (!now) {
      problems.push(`attempt ${old.id} is gone`);
      continue;
    }
    const changed = ATTEMPT_FIELDS.filter((field) => now[field] !== old[field]);
    if (changed.length > 0) problems.push(`attempt ${old.id} changed: ${changed.join(', ')}`);
  }
  const kept = before.map((a) => a.id).filter((id) => afterIds.includes(id));
  if (afterIds.slice(0, kept.length).join() !== kept.join()) problems.push(`the earlier attempts are not first, in their order: ${afterIds.join(', ')}`);
  const added = after.filter((a) => !before.some((b) => b.id === a.id));
  if (added.length === 0) problems.push('no new attempt after the replay');
  else if (added[added.length - 1].outcomeClass !== 'SUCCESS') problems.push(`last new attempt ${added[added.length - 1].outcomeClass}, not SUCCESS`);
  return problems;
}

/** The delivery's pins { mappingVersionId, contractVersionId } before and after the replay: set, and unchanged. */
export function pinProblems(before, after) {
  const problems = [];
  for (const key of ['mappingVersionId', 'contractVersionId']) {
    if (!before[key]) problems.push(`${key} is not pinned on the delivery`);
    else if (after[key] !== before[key]) problems.push(`${key} moved ${before[key]} -> ${after[key]}`);
  }
  return problems;
}

/**
 * New audit rows of the tenant { id, tenantId, actor, action, text } (text = target ids + before/after
 * state): exactly ONE names the delivery, with the actor, tenant, action and both pins.
 */
export function auditProblems(rows, { tenantId, actor, action, deliveryId, pins }) {
  const naming = rows.filter((row) => String(row.text ?? '').includes(deliveryId));
  if (naming.length !== 1) return [`${naming.length} audit events name delivery ${deliveryId}, not 1`];
  const [row] = naming;
  const problems = [];
  if (row.tenantId !== tenantId) problems.push(`audit event of tenant ${row.tenantId}, not ${tenantId}`);
  if (row.actor !== actor) problems.push(`audit actor ${row.actor}, not ${actor}`);
  if (row.action !== action) problems.push(`audit action ${row.action}, not ${action}`);
  for (const key of ['mappingVersionId', 'contractVersionId']) {
    if (!pins[key] || !String(row.text).includes(pins[key])) problems.push(`the audit event does not carry the pinned ${key} ${pins[key]}`);
  }
  return problems;
}

/** Keys of a snapshot whose value differs (deep, by JSON) - [] means nothing changed. */
export function changedKeys(before, after) {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])];
  return keys.filter((key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]));
}
