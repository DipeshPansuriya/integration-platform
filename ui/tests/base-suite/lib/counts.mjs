// T130 (row 323, F21 BS-B, BS-12) - RED stub. The dashboard-count oracle; the behaviour is defined
// by tests/base-suite/unit/counts.test.mjs.

const red = (name) => () => {
  throw new Error(`RED (row323 T130): ${name} not implemented`);
};

export const deriveCounts = red('deriveCounts');
export const countsOf = red('countsOf');
export const differences = red('differences');
export const delta = red('delta');
export const provenanceGaps = red('provenanceGaps');
