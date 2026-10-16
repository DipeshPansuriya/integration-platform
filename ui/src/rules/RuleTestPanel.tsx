// T-028 (E6-F3-S1, lane D): the ALWAYS-VISIBLE test panel of the rules editor - beside the tables, held
// in view (sticky), never behind a toggle. It runs the SAVED rule set on the runtime evaluation port
// (testPanel.ts) and shows the verdict as canonical JSON. Selector contract (lane C's parity e2e):
// [data-test-panel], [data-test-panel-input], [data-test-panel-run], [data-test-panel-verdict] with
// data-state "done" | "error" ("idle" before the first run).
// Row 310 T102: a token-styled card (rules-editor.css .rules-test-panel), sticky from the stylesheet.

import { useState } from 'react';
import { api } from '../api/client';
import { runSample, type PanelResult } from './testPanel';

export function RuleTestPanel({ ruleSetId, sample }: { ruleSetId: string; sample: string }) {
  const [input, setInput] = useState(sample);
  const [result, setResult] = useState<PanelResult | null>(null);
  const [running, setRunning] = useState(false);

  async function run() {
    setRunning(true);
    try {
      setResult(await runSample(api, ruleSetId, input));
    } catch (e) {
      setResult({ state: 'error', text: `the evaluation could not run: ${(e as Error).message}` });
    } finally {
      setRunning(false);
    }
  }

  return (
    <aside data-test-panel className="rules-card rules-test-panel" aria-labelledby="rules-test-panel-title">
      <h3 id="rules-test-panel-title">Test this rule set</h3>
      <p className="rules-muted">Runs the saved rule set on the same evaluation port the runtime uses.</p>
      <label>
        Sample payload (JSON)
        <textarea data-test-panel-input rows={8} spellCheck={false} value={input} onChange={(e) => setInput(e.target.value)} />
      </label>
      <button type="button" className="btn btn-primary" data-test-panel-run disabled={running} onClick={() => void run()}>
        {running ? 'Running...' : 'Run'}
      </button>
      <pre data-test-panel-verdict data-state={result?.state ?? 'idle'} aria-live="polite">
        {result?.text ?? ''}
      </pre>
    </aside>
  );
}
