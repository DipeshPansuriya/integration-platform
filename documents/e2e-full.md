# The full end-to-end suite - the project-close gate (E18, tracker F19)

The project closes when this suite is green on the current commit (backlog E18-F1-S1, G7).

## The one command

```
build-checks/e2e-full-gate.sh
```

It waits for the PC-wide Docker lock (R06), runs `./mvnw -o -Pe2e-full -pl platform-app -am test`, releases the lock,
then judges `platform-app/target/e2e-full/outcomes.tsv` against `build-checks/e2e-full-required.txt`. Exit 0 only when
every required scenario PASSED; FAILED, BLOCKED (waiting on a named lane), ABORTED, DISABLED and NOT_RUN all block the
close. The verdict logic is proven without Docker by `build-checks/test-e2e-full-gate.sh`.

Toolchain: `export JAVA_HOME=...jdk-25...; export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC; export MAVEN_OPTS=-Xmx768m`.
Adapter gates not yet wired on `main` are switched on with `-De18.gates=kafka,db,file` (default `kafka`).

## What it runs (`platform-app/src/test/java/io/integrationplatform/e18/`)

| Story | Class | Scenarios |
|---|---|---|
| E18-F1-S1 (T-151) | `AllTargetTypesJourneyContainerTest` | API, MQ-CSV, Kafka topic, DB, File: intake to delivered, trace id carried, tracking and report reads show the journey |
| E18-F1-S2 (T-152) | `HourLongOutageContainerTest` | a target, Kafka, the JMS broker down for an hour of simulated time |
| E18-F1-S3 (T-153) | `MemoryBudgetContainerTest`, `MemoryBudgetTest` | a bounded load inside the stated heap, pool and queue budget; over budget fails and reports the peak |
| UI leg | `e2e-ui/tests/e2e-full/` | Playwright, reusing the base-suite seed; not edited in `e2e-ui/tests/base-suite` |

A scenario annotated `@BlockedBy(lane, assertion)` still runs unweakened; its failure is recorded BLOCKED-BY with the
exact assertion. Only a PASS closes the project.
