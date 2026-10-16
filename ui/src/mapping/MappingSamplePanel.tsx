// T92 (row 278 close-E7, T-162 gap 1): "Run sample" on the mapping designer - the rules editor's test
// panel pattern (RuleTestPanel.tsx). Runs the SAVED mapping on POST /api/v1/admin/mappings/{id}/test
// (sampleRun.ts) and shows the status and the output, the engine's error, or the refusal.
// Selector contract: [data-sample-panel], [data-sample-input], [data-sample-run], [data-sample-result]
// with data-state idle | ok | error | refused.

import { useState } from 'react';
import { api } from '../api/client';
import { runMappingSample, type SampleResult } from './sampleRun';

export function MappingSamplePanel({ mappingId, dirty }: { mappingId: string | null; dirty: boolean }) {
  const [input, setInput] = useState('');
  const [result, setResult] = useState<SampleResult | null>(null);
  const [running, setRunning] = useState(false);

  async function run() {
    setRunning(true);
    try {
      setResult(await runMappingSample(api, mappingId, input, dirty));
    } finally {
      setRunning(false);
    }
  }

  return (
    <section data-sample-panel className="mapping-card mapping-sample">
      <h3>Run a sample</h3>
      <p className="mapping-muted">Runs the saved mapping on one sample message - save the drawing first.</p>
      <label>
        Sample message (JSON)
        <textarea
          data-sample-input
          rows={6}
          value={input}
          onChange={(e) => setInput(e.target.value)}
        />
      </label>
      <button type="button" className="btn" data-sample-run disabled={running} onClick={() => void run()}>
        Run sample
      </button>
      <div data-sample-result data-state={result?.state ?? 'idle'} aria-live="polite">
        {result?.status && <p data-sample-status>Status: {result.status}</p>}
        {result?.state === 'ok' ? (
          <pre data-sample-output className="mapping-sample-output">
            {result.text}
          </pre>
        ) : (
          result && <p role="alert" className="mapping-alert mapping-alert-danger">{result.text}</p>
        )}
      </div>
    </section>
  );
}
