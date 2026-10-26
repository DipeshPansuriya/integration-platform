# e2e-ui inventory (L29, row 372 / T210)

Read-only inventory taken on origin/main 0b4cbf04 before any move. Rule applied: a file that imports `ui/src`
(or reads it as source) stays in `ui/`; everything that only drives a booted backend / the built console moves
to the new top-level `e2e-ui/` (same depth as `ui/`, so every `../..` path to the repo root stays valid).
`e2e-ui/` mirrors the old layout (`e2e-ui/tests/<suite>`, `e2e-ui/scripts`) so imports between moved files do not change.

## ui/tests

| Folder | Files | Imports ui/src | Used by | Gate / doc reference | Decision |
|---|---|---|---|---|---|
| screens | 23 | no (boot-backend, console-server) | `playwright.config.mjs`, `test:screens`, `test` (unit/screen-hosts) | design.md, PRD.md | move |
| base-suite | 75 | no (boot-backend) | `playwright.base-suite.config.mjs`, front config, run-tue.sh | Release1ScenariosContainerTest comments | move |
| security | 7 | no | `playwright.security.config.mjs` | - | move |
| release1 | 11 | no (scripts/release1/*) | `playwright.release1.config.mjs`, `test:release1-journey`, `test` (unit) | run-release1-gate.mjs (pending-parity reads Release1Pending.java) | move |
| demo | 5 | no | `playwright.e2e-demo.config.mjs` (inside the folder) | - | move |
| e2e-full | 3 | no (base-suite/lib) | `playwright.e2e-full.config.mjs`, `test:e2e-full-ui` | documents/e2e-full.md | move |
| mocked | 1 | no | `playwright.mocked.config.mjs`, `test:mocked` (vite preview of ui/dist) | - | move |
| trial | 5 | no (trial-up, boot-backend) | `test`, `test:trial` | - | move |
| perf | 1 | no | `perf`, `run-perf-suite.mjs` | design.md | move |
| slow-page-negative.test.mjs, openapi-contract.docker.test.mjs | 2 | no (measure-p95; boot-backend + typescribe) | `test`, `test:container`, `test:perf-negative` | - | move (typescribe stays in ui, imported by path) |
| ui | 39 | 28 yes, 10 read src as text, 1 (keycloak-theme) no | `test` | - | stay, except keycloak-theme.test.mjs (imports keycloak-harness only) which moves |
| mapping | 8 | yes | `test` | emit.ts comment | stay |
| rules | 10 | 2 yes; licence-check uses check-licences; ts-resolve.mjs loader | `test` | - | stay |

## ui/scripts

| File | Used by | Decision |
|---|---|---|
| boot-backend.mjs, keycloak-harness.mjs | every container suite | move |
| run-release1-gate.mjs, run-release1-journey.mjs, release1/* (8) | `test:release1-journey`; the frozen Release 1 proof | move (paths only) |
| trial-up.mjs, measure-p95.mjs, run-perf-suite.mjs, preview-server.mjs | `trial`, `perf`, `test:perf` | move |
| check-licences.mjs | `build`, `licence:check` | stay (product build) |
| save-openapi.mjs, typescribe.mjs | `generate:client` | stay (product) |

## Other references

`build-checks/e2e-full-gate.sh` and the Java `platform-e2e` scenarios do not name `ui/` paths (only comments in
Release1Pending.java and Release1ScenariosContainerTest.java). `arch-test` `ModuleScopeGuard` treats `ui/` as a
production root; `e2e-ui/` is outside it (test-only, like platform-e2e). No `.github` workflows exist.
`ui/package.json` keeps every script name: moved ones become `npm --prefix ../e2e-ui run <same name>` delegates.
