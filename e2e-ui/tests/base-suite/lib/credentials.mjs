// lane qa-release (2026-10-22): which source credential a scenario rotates (see unit/credentials.test.mjs).
// A rotation leaves the old credential ROTATING and issues an ACTIVE replacement; the same seeded id rotated
// twice is a 409 "already being rotated" - so each scenario file rotates the CURRENT active credential.

/** The id of the newest ACTIVE credential in `list` (the source system's credentials); `fallbackId` when the list is empty or unreadable. */
export function activeCredentialId(list, fallbackId) {
  if (!Array.isArray(list) || list.length === 0) return fallbackId;
  const active = list.filter((credential) => credential?.status === 'ACTIVE');
  if (active.length === 0) throw new Error('no ACTIVE source credential to rotate - every credential of the source system is ROTATING or REVOKED');
  active.sort((a, b) => String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')));
  return active[0].id;
}
