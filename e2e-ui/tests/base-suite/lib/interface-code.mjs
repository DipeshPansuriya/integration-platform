// lane qa-release (2026-10-22, reworded after PR 57): the code the common intake is addressed by. A typed flow code
// is validated and kept as the interface code (PR 57); a flow created with no code gets a generated one (flow-<uuid>).
// Either way the seed records the code the interface really has, and POST /api/v1/interfaces/{code}/messages must use
// that one - otherwise 404 INTERFACE_NOT_FOUND "no published interface '<code>' on this tenant".

/** The intake code of one tenant's seeded flow, from the run file's seed (`key` is "A" or "B"). */
export function interfaceCodeOf(run, key) {
  const code = run?.seed?.[key]?.interfaceCode;
  if (typeof code !== 'string' || code.trim() === '') {
    throw new Error(`tenant ${key}: the seed did not record the flow's interface code - the intake cannot be addressed`);
  }
  return code;
}
