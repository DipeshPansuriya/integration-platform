# NESTED-APPOINTMENT - hosted showcase seed (hand-off for the hosted run)

What it is: a showcase flow that ingests one appointment document with two array levels (`VehicleList[]`, each with `ContainerList[]`) and delivers it flattened to a JSON ARRAY with one flat record per container (20 fields: the appointment and vehicle fields repeated on each record). Test data only, no real personal data. Not yet run on the hosted app: that is a separate go from Dipesh/Maya. Nothing here is run by the build.

Files (all under `e2e-ui/`): `scripts/seed-nested-appointment-hosted.mjs` (the driver), `scripts/release1/seed-nested-appointment.mjs` (the seed, tested with a fake admin API), `scripts/release1/nested-appointment/sample.json` (the source sample) and `expression.jsonata` (the mapping, a raw JSONata expression; the drawn-links designer cannot repeat a parent field per child, so open it in the raw editor and do not re-save it from the graph view).

## Steps (owner, after the stack is healthy with `-f demo/compose.demo.yaml` and `./seed/seed-showcase.sh` has run)

1. **Target stub (WireMock is internal only, no published port).** On the server, in `/docker/ipass`:
   ```
   docker compose -f compose.yaml -f demo/compose.demo.yaml exec -T wiremock curl -s -X POST http://localhost:8089/__admin/mappings -H 'content-type: application/json' -d '{"request":{"method":"POST","urlPathPattern":"/nested-target/.*"},"response":{"status":200},"priority":10}'
   ```
   `nested-target` accepts one JSON-array POST per delivery and answers 200; WireMock's request journal keeps what arrived. There is **no ingress stub**: the source system's `baseUrl` is never dialled, the customer posts to the platform itself (step 4). Use `http://wiremock:8089/nested-source/` for it (the allow-listed demo host).
2. **Run the driver** from a machine that reaches `https://ipass.<domain>`, with the values taken from the OS store / password manager into the shell session (never typed into a file, never pasted into chat):
   - `IPASS_BASE_URL=https://ipass.<domain>`
   - `SEED_AUTHOR_USER=showcase-admin-a` and `SEED_AUTHOR_PASSWORD` (the `SHOWCASE_PASSWORD_ADMIN_A` value). It must be the TENANT_ADMIN: publishing and issuing the source credential need that role, a CONFIGURATOR is refused (403)
   - `SEED_APPROVER_USER=showcase-approver-a` and `SEED_APPROVER_PASSWORD` (the `SHOWCASE_PASSWORD_APPROVER_A` value)
   - `SEED_TARGET_BASE=http://wiremock:8089/nested-target/`, `SEED_SOURCE_BASE=http://wiremock:8089/nested-source/`
   - then `cd e2e-ui && node scripts/seed-nested-appointment-hosted.mjs` (`--print-plan` first: lists the steps and the env names, calls nothing).
   It logs in through the realm login form like the console does (a cookie session plus the CSRF header), as the two hosted realm users of tenant `showcase-a`. These are NOT the e2e boot logins `default` / `approver`, which exist only inside the test harness. Author and approver are different users (four-eyes); both are created with these roles by `./seed/seed-showcase.sh` (`seed.sql`: `showcase-admin-a` TENANT_ADMIN, `showcase-approver-a` APPROVER), and a test reads the code's role table to keep this true. Every request is https-only, stays on the app's own domain and has a timeout; a failure prints one short line (step, HTTP status), never a stack or a secret. Re-running is safe: a published flow is skipped; a draft left by a run that died is resumed and published. If contract version 1.0 of `nested-source` or `nested-target` already exists with different content, the run stops before writing anything and says so (retire it or use another version label).
3. **The source credential** is printed once on the terminal as client id and secret. Put it straight into the OS store / password manager; never redirect the output to a file, never paste it in chat. Lost: issue a new one with `POST /api/v1/admin/systems/{nested-source id}/source-credentials` (body `{"customerId": "..."}`). A re-run of a finished seed prints no credential. **If the final step failed or its answer was lost, the server may still have issued a credential nobody holds (an orphan):** list them with `GET /api/v1/admin/systems/{nested-source id}/source-credentials`, revoke every one you do not hold with `POST /api/v1/admin/source-credentials/{credentialId}/revoke` (TENANT_ADMIN), then issue a fresh one.
4. **Prove it (optional).** Get a token (client credentials) from `https://auth.<domain>/realms/platform/protocol/openid-connect/token` without putting the secret on a command line: load the client id and secret into shell variables from the password manager (`read -rs`), then feed the form body on stdin, where `printf` is a shell builtin and shows in no process list:
   `printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' "$CID" "$CSECRET" | curl -s -X POST https://auth.<domain>/realms/platform/protocol/openid-connect/token -H 'content-type: application/x-www-form-urlencoded' --data-binary @-` (take `access_token` from the answer; unset the variables afterwards). Then post the sample:
   `POST https://ipass.<domain>/api/v1/interfaces/<interface code printed by the driver>/messages` with headers `Authorization: Bearer <token>`, `X-Source-Contract-Version: 1.0`, `Idempotency-Key: <unique>`, body = `sample.json`. Expect `202` and a tracking id. Then read the journal: `... exec -T wiremock curl -s http://localhost:8089/__admin/requests`.

## Example bodies

Posted (`sample.json`): `{"TerminalOpsId":3,"TerminalId":2,"AppointmentId":90726015,"AppointmentDate":"2026-07-09T11:11:05.834Z","AppointmentStatus":"F","NoofVehicle":1,"BerthNo":"68","VehicleList":[{"SlotDate":"2026-07-09T11:11:05.834Z","SlotStartTime":"2026-07-09T14:00:00.834Z","SlotEndTime":"2026-07-09T15:00:00.834Z","DriverPortId":"907261","ContainerList":[{"BookingType":"FO","TransactionID":13,"ContainerNo":"26","Size":"40","Type":"GB","ISOCode":"1232","NoOfPackages":0,"GrossWeight":0,"ActualWeight":0}]}]}`

Arrives at `nested-target` (one POST, a one-element array, 20 fields, dates unchanged, numbers stay numbers, ids stay strings): `[{"terminalOpsId":3,"terminalId":2,"appointmentId":90726015,"appointmentDate":"2026-07-09T11:11:05.834Z","appointmentStatus":"F","noOfVehicles":1,"berthNo":"68","slotDate":"2026-07-09T11:11:05.834Z","slotStartTime":"2026-07-09T14:00:00.834Z","slotEndTime":"2026-07-09T15:00:00.834Z","driverPortId":"907261","bookingType":"FO","transactionId":13,"containerNo":"26","size":"40","type":"GB","isoCode":"1232","noOfPackages":0,"grossWeight":0,"actualWeight":0}]`

Two vehicles with two containers each give one POST with a 4-element array; a vehicle with an empty `ContainerList` adds nothing; no containers at all gives a POST of `[]`. A `ContainerList` that is null or text counts as no containers.

## Limits to know

- Only `AppointmentId` and `ContainerNo` are guaranteed: the contracts require just those (plus `VehicleList`); a key the source omits is omitted from the record (not null).
- `NoofVehicle` is passed through as sent (`noOfVehicles`), not recounted.
- Whole numbers above 2^53 lose precision in the engine (12345678901234567890 becomes 1.2345678901234567E19). The 8-digit ids of the sample are safe; keep ids that long as strings.
- A run that dies between publishing and issuing the credential reports "already published" on re-run and issues no credential: issue one with the call in step 3.
- Never run any of this from the build, from CI or against the hosted app without the owner's go.
