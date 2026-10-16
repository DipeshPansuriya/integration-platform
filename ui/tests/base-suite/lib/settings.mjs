// T130 (row 323, F21 BS-B) - RED stub. The one place the base suite reads the dev-only outbound
// exception from; the behaviour is defined by tests/base-suite/unit/settings.test.mjs.

const red = (name) => () => {
  throw new Error(`RED (row323 T130): ${name} not implemented`);
};

export const EXCEPTION_ENV = null;
export const NOT_CONFIGURED = null;
export const exceptionSettings = red('exceptionSettings');
export const requireException = red('requireException');
export const targetUrl = red('targetUrl');
export const assertRealResolver = red('assertRealResolver');
