// T130 (row 323, F21 BS-B) - RED stub. Pure helpers for the one local WireMock (stub mappings,
// request journal); the behaviour is defined by tests/base-suite/unit/wiremock.test.mjs.

const red = (name) => () => {
  throw new Error(`RED (row323 T130): ${name} not implemented`);
};

export const WIREMOCK_IMAGE = null;
export const STUB_IDS = null;
export const stubMapping = red('stubMapping');
export const header = red('header');
export const deliveriesTo = red('deliveriesTo');
export const requestsTo = red('requestsTo');
