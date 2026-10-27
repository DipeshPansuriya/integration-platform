# Outside Postgres access (Phase 2) - design, apply, rollback, verification

Status: PREPARED, NOT APPLIED. Ledger row 473, project ipass/integration-platform. Approved by the owner: option B, Postgres reachable from ANY IP (no IP allow-list), with safeguards. Everything here is names and commands only; no secret appears in this folder. Applying (Phase 2b) is a separate step that needs the security review (Karan) first. Proof so far: `bash deploy/hostinger/test/outside-access.sh` (real roles, real Flyway SQL of every module, real pg_hba, TLS and HAProxy, on the build PC under the Docker lock).

## 1. Design note - how 5432 is exposed

Constraints: `ipass-data` stays `internal: true`; no other service's isolation changes; the shared Traefik is not touched (it cannot carry raw Postgres without TLS passthrough and an entrypoint on its static config).

| Option | Verdict |
|---|---|
| A. Put `postgres` on a non-internal network and publish 5432 | Rejected: the database container gains a default route (egress) and a public listener; `postgres` would also stay on one network with everything it publishes to |
| B. A TCP front container `pg-front` (HAProxy, TCP mode) on a NEW internal network `ipass-pgfront` shared with postgres only, plus a NEW network `ipass-pgpublic` (outbound NAT switched off) that only `pg-front` joins; only `pg-front` publishes `5432` | **Chosen.** `ipass-data` untouched; postgres gains one more INTERNAL network; the proxy cannot reach the internet; postgres itself does TLS and pg_hba |
| C. Traefik TCP router with TLS passthrough | Rejected: changes the shared Traefik static config (forbidden) |

Why HAProxy and not nginx `stream`: per-source `conn_cur` / `conn_rate` tracking and a hard `maxconn` are built in. The proxy hides the real client address from Postgres (Postgres sees the proxy's address on `ipass-pgfront`), so `pg_hba` identifies "the outside path" by that fixed subnet (`IPASS_PGFRONT_SUBNET`, a /28, chosen free on the server). Nothing else can be on that subnet.

Files: `compose.outside.yaml` (opt-in last `-f`, base `compose.yaml` untouched), `haproxy.cfg`, `pg_hba.conf.tmpl` + `render-pg-hba.sh`, `make-tls-cert.sh`, `outside-role.sql` / `outside-role-drop.sql` / `outside-role.sh`, `pg-throttle.sh`.

TLS and auth: `ssl=on`, TLS >= 1.2, `password_encryption=scram-sha-256`, `authentication_timeout=10s`, connection logging on. `hostssl` for the one allowed rule, a `reject` for the rest of the proxy subnet (so non-TLS is refused).

**Certificate.** Recommended: a self-signed ECDSA certificate generated on the server (`make-tls-cert.sh`), SAN = the VPS IPv4 (and `db.<domain>` if a name is added later), no DNS change. Clients PIN it (`sslrootcert=server.crt`, `sslmode=verify-full`): this authenticates the server and defeats a man-in-the-middle as long as the certificate file reached the client over a trusted channel. It is weaker than a public-CA certificate in one way only: distribution and renewal (yearly) of the file is manual. `sslmode=require` without the pinned file is encryption only, no server authentication, and is weaker (an active attacker can sit in the middle); do not recommend it.
`Needs decision: public-CA certificate on a name db.<domain>? It needs a DNS A record (a DNS change, outward) plus a way to issue and renew the certificate (Traefik's ACME store is not touched; webroot or DNS-01 would have to be built) (recommend: start with the pinned self-signed certificate, add the name later)`.

## 2. What the outside role sees (row-level security, from the policies)

Role `ipass_outside`: LOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEDB, NOCREATEROLE, NOREPLICATION, NOINHERIT, CONNECTION LIMIT 3, member of no role, so it inherits none of `ipaas_app`'s grants (`outside-role.sql`).

- Every tenant table has the policy `USING (tenant_id = current_setting('app.tenant_id')::uuid)` with NO `TO` clause, so it applies to every role including this one: `platform-tenancy/src/main/resources/db/migration/V1_3_2__tenant_directory_and_app_user_rls.sql:35-39`, `platform-config/.../V2_2_1__config_systems.sql:52-58`, `.../V2_2_3__config_flows.sql:73-83`, `platform-routing/.../V2_1_810__message_partitioned.sql:55-56`, `platform-delivery/.../V2_3_1__delivery_partitioned.sql:69-70`, `.../V2_3_2__delivery_attempt_append_only.sql:43-45`, `.../V2_4_1300__delivery_daily_aggregate.sql:26-28`. The error-deny form is stated at `V1_3_2...sql:26-27`: with the setting unset, a query FAILS (no rows, an error); it does not return all rows.
- The setting is an ordinary custom parameter that any session may `SET`. So the role default alone is not a boundary. `outside-role.sql` therefore sets `app.tenant_id` as the role's default for the database AND adds a RESTRICTIVE policy `outside_pin ... TO ipass_outside USING (tenant_id = '<that tenant>')` on each of the nine granted tables (AND-ed with the app's policy). Result, tested: exactly ONE tenant (the code given to `outside-role.sh up`), and `SET app.tenant_id = '<other tenant>'` returns zero rows.
- Without the pin and without any setting the role would see nothing (errors). With the pin it sees one tenant; never all.
- `Needs decision: which tenant may the outside role read (recommend showcase-a; the data is seed/demo only)`. The script takes the tenant code as an argument, so this is one word at apply time.

Granted (SELECT only, named columns, nine tables): `tenant` (id, code, status, time_zone); `system` (not `settings`); `target_endpoint` (not `credential_ref`, `error_dictionary`); `interface`; `route` (not `condition`); `message` (metadata only; the `payload` table is not granted); `delivery` (not `claim_token`); `delivery_attempt` (not `detail`); `delivery_daily_aggregate`. Why these: they answer "what is configured and how are deliveries doing" and carry no bodies, credentials, hashes or tokens. Because grants are per column, `SELECT *` on the column-limited tables is refused by design; clients name their columns.
Not granted and asserted unreachable by the script itself (it fails the run otherwise) and by the test: `source_credential` (V2_3_4), `target_credential` (V2_3_7), `app_user`, `role_grant`, `audit_event`, `audit_outbox`, `payload`, `outbox`, `idempotency_key`, `intake_idempotency`, `mapping_version`, `mapping_sample_run`, `contract_version`, `db_target_approved_table`, every `scheduler.*` table (incl. `operator_credential`) and `console_session.*` (session blobs). The SECURITY DEFINER functions are `REVOKE ... FROM PUBLIC` in their migrations, so they are not callable (tested). TEMPORARY on the database is withdrawn from PUBLIC and granted explicitly to `ipaas_app`, `ipass_backup`, `ipass_restore` (the owner and superuser keep it), so the outside role cannot fill the disk with temp tables.
Session defaults on the role (statement_timeout 15s, idle_in_transaction_session_timeout 30s, idle_session_timeout 10min, lock_timeout 2s, default_transaction_read_only, temp_file_limit 32MB, work_mem 4MB) are defaults a client can change except `temp_file_limit`; the hard limits are the grants, CONNECTION LIMIT 3, the proxy and the host throttle.

## 3. pg_hba.conf (`pg_hba.conf.tmpl`)

Order matters (first match). (1) `hostssl ipass ipass_outside <pgfront> scram-sha-256`; (2) `host all all <pgfront> reject` - every other role (ipass_admin, ipass_backup, ipass_app, ipaas_app, ipass_migrator, ipass_restore, keycloak), every other database (keycloak, postgres, template*) and non-TLS from the outside path; (3) `host all ipass_outside all reject` - no other way in; (4) `host all all all scram-sha-256` - the internal networks exactly as today. The container-local and loopback lines keep the image behaviour. The file is mounted with `-c hba_file=...`; the original in the data directory is not edited, which makes rollback a removal of that flag.

## 4. Throttle (`pg-throttle.sh`)

Two layers, every IP allowed. HAProxy (`haproxy.cfg`): at most 3 simultaneous and 10 new connections per 10 s per source, 20 in total, so a flood cannot take more than 20 of Postgres' 50 slots. Host DOCKER-USER chain (Docker publishes bypass ufw), IPv4 only because the port is published on IPv4 only:
```
iptables -N IPASS-PG
iptables -A IPASS-PG -m connlimit --connlimit-above 4 --connlimit-mask 32 -j DROP
iptables -A IPASS-PG -m hashlimit --hashlimit-name ipass-pg --hashlimit-mode srcip --hashlimit-above 12/minute --hashlimit-burst 6 -j DROP
iptables -A IPASS-PG -j RETURN
iptables -I DOCKER-USER 1 -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j IPASS-PG
```
Remove (rollback): `iptables -D DOCKER-USER -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j IPASS-PG; iptables -F IPASS-PG; iptables -X IPASS-PG` (= `pg-throttle.sh off`). Persistence across a reboot: a systemd oneshot (`After=docker.service`, `ExecStart=/docker/ipass/outside/pg-throttle.sh on`, `ExecStop=... off`, `RemainAfterExit=yes`); after any `systemctl restart docker` check `pg-throttle.sh status`. Wrong passwords are throttled only through these new-connection limits (an attacker with many addresses still gets 12/min each); the defence against guessing is a 48-character random password under scram-sha-256.

## 5. Secret

`outside-role.sh up` generates `OUTSIDE_DB_PASSWORD` ON THE SERVER (48 random alphanumerics), applies it through the environment (`\getenv`, never a command line), appends it to `/docker/ipass/.env` (mode 600, same pattern as `bootstrap-env.sh` / `rotate-secret.sh`) and prints only an 8-character fingerprint to `.rotation-log`. Rotation: `./rotate-secret.sh OUTSIDE_DB_PASSWORD`. Dipesh retrieves it with (over SSH, prints it to his terminal only):
`ssh <vps> "sudo sed -n 's/^OUTSIDE_DB_PASSWORD=//p' /docker/ipass/.env"`
then stores it in his password manager and gives it to a user through the OS store, never chat or mail.

## 6. Apply plan (Phase 2b - not before the review; on the VPS in /docker/ipass)

0. Read-only checks: fit rule of the README (memory, disk); pick a free /28 (`docker network inspect $(docker network ls -q) --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}'`, `ip route`); `iptables -V` and that `DOCKER-USER` exists; compare the running `pg_hba.conf` (`docker compose exec postgres cat /var/lib/postgresql/data/pg_hba.conf`) with the internal lines of the template - stop if it differs.
1. Backup: `B=/docker/ipass-backups/$(date -u +%Y%m%dT%H%M%SZ)-t473; mkdir -m 700 $B`; copy `compose.yaml`, `.env`, `demo/compose.demo.yaml` and the live `pg_hba.conf` (`docker compose cp postgres:/var/lib/postgresql/data/pg_hba.conf $B/`); ONE dump: `docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh`.
2. Copy the `outside/` folder from the merged branch to `/docker/ipass/outside/`; `chmod +x outside/*.sh`.
3. Certificate: `./outside/make-tls-cert.sh ./outside/tls <VPS-IPv4>` (as root; owner 70:70). Write down its SHA-256 fingerprint.
4. `echo IPASS_PGFRONT_SUBNET=<free /28> >> .env`; `IPASS_PGFRONT_SUBNET=<same> ./outside/render-pg-hba.sh`.
5. `F="-f compose.yaml -f demo/compose.demo.yaml -f outside/compose.outside.yaml"`; `docker compose $F config -q` (prints nothing).
6. Recreate ONLY postgres: `docker compose $F up -d --no-deps postgres`; wait healthy (about 30 s). Check: `show ssl` is on, `show hba_file`, the app is healthy (`docker compose ps`), `https://ipass.<domain>` 200, `https://auth.<domain>/realms/platform/account` 200. Any failure: go to rollback R3 now (the app and Keycloak now negotiate TLS to postgres with the JDBC default; this step proves it works).
7. `./outside/outside-role.sh up <tenant_code>` (fingerprint printed; password stays in `.env`).
8. `sudo ./outside/pg-throttle.sh on`; install and enable the systemd unit of section 4.
9. Start the door: `docker compose $F up -d --no-deps pg-front`. Check `ss -ltn 'sport = :5432'` (IPv4 only), `docker exec <pg-front> wget -T3 -qO- http://1.1.1.1` must FAIL (no egress).
10. Open the provider firewall: add an inbound TCP 5432 rule (IPv4, any source) to the VPS firewall group and wait for `synced`. This is the one outward change and needs Dipesh's approval at the permission prompt; the DNS record, if the CA route is chosen, is the other.
11. Karan runs section 8 from outside. Hand the certificate file (public) and, separately, the password to the user.

## 7. Rollback plan (in this order; each step is safe to repeat)

R1. Remove the provider firewall rule for 5432; wait for `synced`.
R2. `docker compose $F rm -sf pg-front` (the published port closes).
R3. `sudo ./outside/pg-throttle.sh off` (and disable the systemd unit).
R4. `./outside/outside-role.sh down` (drops the role, the nine `outside_pin` policies, the role settings, restores TEMPORARY to PUBLIC, removes the `.env` line).
R5. Recreate postgres WITHOUT the override: `docker compose -f compose.yaml -f demo/compose.demo.yaml up -d --no-deps postgres`. The original command and the original `pg_hba.conf` in the data directory are back; compare with `$B/pg_hba.conf` (`diff`).
R6. `docker network rm ipass_ipass-pgfront ipass_ipass-pgpublic`; delete `outside/tls`, `outside/pg_hba.conf`; remove `IPASS_PGFRONT_SUBNET` from `.env`.
R7. If anything is wrong with the data: restore `compose.yaml`, `.env`, `demo/compose.demo.yaml` from `$B` and restore the single pre-change dump (README "Update and roll back" 4). Verify: `ss -ltn` shows no 5432, the three public links answer as before, the restore test passes.

## 8. Verification list for Karan (from OUTSIDE; `H` = host or IP, `PW` = outside password, `CRT` = pinned server.crt)

1. TLS ok: `PGPASSWORD=$PW psql "host=H dbname=ipass user=ipass_outside sslmode=verify-full sslrootcert=CRT" -c "select ssl, version from pg_stat_ssl where pid = pg_backend_pid()"` is `t`, TLS 1.2 or 1.3; `openssl s_client -starttls postgres -connect H:5432` shows the expected fingerprint and refuses TLS 1.0/1.1.
2. Plain refused: same with `sslmode=disable` gives `pg_hba.conf rejects connection ... no encryption`.
3. Read-only: `begin read write; insert / update / delete / truncate / create table / create temp table / drop` each give `permission denied`.
4. Secrets unreachable: `select * from source_credential`, `target_credential`, `app_user`, `role_grant`, `audit_event`, `payload`, `scheduler.operator_credential`, `console_session.spring_session`, `select claim_token from delivery`, `select settings from system` all give `permission denied`.
5. One tenant: `select code from system` returns only the pinned tenant; `set app.tenant_id='<another uuid>'; select count(*) from system` returns 0.
6. Other roles refused with a bogus password for `ipass_admin`, `ipass_backup`, `ipass_app`, `ipaas_app`, `ipass_migrator`, `ipass_restore`, `keycloak`: the answer is `pg_hba.conf rejects connection` (a rejection BEFORE any password check, not `password authentication failed`).
7. Other databases refused for `ipass_outside`: `dbname=keycloak`, `postgres`, `template1` give `pg_hba.conf rejects connection`.
8. Throttle: 30 wrong-password attempts in 10 s from one host - most are cut at connect (reset/timeout) and `iptables -L IPASS-PG -v` on the server shows drop counters; 4 parallel sessions - the 4th is refused; `show statement_timeout` is 15s and `select pg_sleep(20)` is cancelled at 15 s.
9. Surface: an IPv4 scan shows only 22, 80, 443, 5432 open; `nc -6 -vz H 5432` fails; the three public links and `/admin` 404 answer exactly as before.
10. After rollback: `nc -vz H 5432` times out or is refused; `psql` fails to connect; on the server `ss -ltn` has no 5432, `select count(*) from pg_roles where rolname='ipass_outside'` is 0.

## 9. Known limits (honest)

- Any-IP exposure remains an attack surface: unauthenticated TLS handshakes cost CPU, and many addresses together can fill the proxy's 20 slots (outside access stops; the internal stack is unaffected because Postgres keeps 30 slots).
- Client-side session defaults can be changed by the client; the grants are the real barrier. The pin policies live outside Flyway; a future migration that recreates a table drops its pin and grant (the role then fails closed). Re-run `outside-role.sh up <tenant>` after a deploy that adds columns the role should see.
- IPv4 only; the certificate is self-signed unless the DNS decision is taken; `log_connections` adds one log line per connection to journald (30-day retention).
- Not verified without a live host: the `enable_ip_masquerade=false` effect and the DOCKER-USER rules under the host's iptables backend (checked in apply steps 9 and 8 and in verification 8).
