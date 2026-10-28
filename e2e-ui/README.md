# e2e-ui

The console's end-to-end suites, moved out of `ui/` (L29, row 372). Test-only, like `platform-e2e`: it drives a booted
backend and the BUILT console in `../ui` (`ui/dist`, `ui/public/console-config.json` are still written there by the
global-setups). `ui/` keeps product code and the product unit tests that import `ui/src`.

Layout (same depth as `ui/`, so repo-root-relative paths are unchanged): `playwright.*.config.mjs`, `tests/<suite>`
(screens, base-suite, security, release1, demo, e2e-full, mocked, trial, perf, ui/keycloak-theme), `scripts/` (boot-backend,
keycloak-harness, run-release1-gate, run-release1-journey, release1/*, trial-up, measure-p95, run-perf-suite, preview-server).

Install: `npm ci` here (own lock; same npm cache as `ui/`; offline works from the cache). Needs `../ui` installed too (vite config, build).

**Both folders need `npm ci`:** `ui` `npm test` ends by running `npm --prefix ../e2e-ui test`, so a checkout with only `ui` installed fails it (run `npm run test:product` there for the ui-only tests). Extra arguments forward through the `ui` delegates: `cd ui && npm run test:screens -- --list` (also `-- login.docker.spec.mjs`, `test:release1-journey -- <log>`).

| Command (run in `e2e-ui/`) | Was (in `ui/`) |
|---|---|
| `npm test` (offline `node --test` unit tests; the count is printed at the end of the run, 112 on 2026-10-27) | part of `npm test` |
| `npm run test:screens` / `test:mocked` / `test:e2e-full-ui` | same names |
| `npm run test:release1-journey -- <log>` / `node scripts/run-release1-gate.mjs <log>` | same names |
| `npx playwright test -c playwright.base-suite.config.mjs` / `playwright.security.config.mjs` / `playwright.release1.config.mjs` | same files |
| `npm run test:container` / `test:trial` / `trial` / `perf` / `test:perf` | same names |

From `ui/` every old script name still works: it runs `npm --prefix ../e2e-ui run <name>` (extra args: run it in `e2e-ui/` instead).
`ui` `npm test` = its own tests, then the ones here; `npm run test:product` runs only the `ui` ones. Counts move with every added test, so read them from the run summary (2026-10-27 on `7b916dd7`: `ui` 387, `e2e-ui` 112).
