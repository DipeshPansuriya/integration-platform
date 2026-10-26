// Row 372: the operator screens in a real browser WITHOUT a backend or Docker. Serves the BUILT console
// (npm run build -> dist) with vite preview; every /api call is answered by the spec's page.route fixtures.
// Complements (never replaces) tests/screens, which drives the real backend and needs the Docker lock.
import { defineConfig } from '@playwright/test';

const PORT = 4181;
export default defineConfig({
  testDir: 'tests/mocked',
  timeout: 20000,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: 'test-results/mocked.json' }]],
  use: { headless: true, baseURL: `http://127.0.0.1:${PORT}` },
  webServer: { cwd: '../ui', command: `npx vite preview --host 127.0.0.1 --port ${PORT} --strictPort`, url: `http://127.0.0.1:${PORT}`, reuseExistingServer: false, timeout: 60000 }
});
