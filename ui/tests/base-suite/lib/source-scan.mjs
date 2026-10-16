// T130 (row 323, F21 BS-B) - RED stub. Static guards over the base suite's own sources and its SQL;
// the behaviour is defined by tests/base-suite/unit/source-scan.test.mjs.

const red = (name) => () => {
  throw new Error(`RED (row323 T130): ${name} not implemented`);
};

export const PIPELINE_TABLES = null;
export const pipelineWrites = red('pipelineWrites');
export const isReadOnlySelect = red('isReadOnlySelect');
export const ipLiterals = red('ipLiterals');
