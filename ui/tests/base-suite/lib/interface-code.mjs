// lane qa-release (2026-10-22): the code the common intake is addressed by. The flow's requested code
// ("orders", FLOW.code) is NOT the interface code on main: the interface gets a generated code
// (flow-<uuid>, read back from the flow create answer), and POST /api/v1/interfaces/{code}/messages must use
// that one - otherwise 404 INTERFACE_NOT_FOUND "no published interface 'orders' on this tenant".

/** The intake code of one tenant's seeded flow, from the run file's seed (`key` is "A" or "B"). */
export function interfaceCodeOf(run, key) {
  const code = run?.seed?.[key]?.interfaceCode;
  if (typeof code !== 'string' || code.trim() === '') {
    throw new Error(`tenant ${key}: the seed did not record the flow's interface code - the intake cannot be addressed`);
  }
  return code;
}
