// T130 (row 323, F21 BS-B) - RED stub. The two-tenant seed table of the base suite; the behaviour
// is defined by tests/base-suite/unit/tenants.test.mjs.

const red = (name) => () => {
  throw new Error(`RED (row323 T130): ${name} not implemented`);
};

export const SOURCE_CONTRACT = null;
export const TARGET_CONTRACT = null;
export const FLOW = null;
export const LINKS = null;
export const LOOP = null;
export const TENANTS = null;
export const TENANT_B_PLAN = null;
export const orderRecord = red('orderRecord');
export const expectedTargetBody = red('expectedTargetBody');
export const forgedRecord = red('forgedRecord');
export const leakHits = red('leakHits');
