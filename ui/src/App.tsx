// T-158 (E3 lane D): the console shell. The flow-definition screen (T-017) and the version +
// approval screens (T-022) attach here; both use ONE imported typed client (src/api/client.ts)
// - never a hand-written fetch, never a mock (E3-F3-S1).

export function App() {
  return <main data-page="console-home">Integration Platform Console</main>;
}