# Outside Postgres access (option A: a separate demo database) - design, apply, rollback, verification

Status: PREPARED, NOT APPLIED. Ledger row 473, project ipass/integration-platform, phase 2a rework after the security review (NO-GO on the in-place design). Nothing here touches the live stack: applying (phase 2b) is a separate step that needs the re-review and Dipesh's approval. Everything in this folder is names and commands; no secret is in it.

## 1. Design - what is exposed

The public door fronts a SEPARATE small Postgres, `pg-demo`, that holds a snapshot copy of the demo tenant `showcase-a` and nothing else. The LIVE Postgres is not exposed, is on none of the new networks, and its roles and `pg_hba.conf` are not changed at all. `outside/compose.outside.yaml` is its own compose project (`name: ipass-outside`): it does not extend `compose.yaml`, so `docker compose up/down` on one never touches the other.

```
internet --5432--> pg-front (HAProxy, TCP) --ipass-pgfront (internal)--> pg-demo (TLS + scram, hostssl, one role)
live postgres, app, keycloak: ipass-data / ipass-work / ...  (not connected to any of the above)
```

| Piece | Facts |
|---|---|
| `pg-front` | the ONLY published port (`0.0.0.0:5432`, IPv4 only); networks `ipass-pgfront` (internal) + `ipass-pgpublic` (bridge `br-ipass-pgpub`, masquerade off); read-only root, no capabilities, 64 MB, 0.2 CPU |
| `pg-demo` | `postgres:17.11-alpine` (the image the live Postgres uses), only on `ipass-pgfront`; own named volume `ipass-outside_pg-demo-data`; 256 MB (no swap), 0.25 CPU, 100 pids; `max_connections=15` (3 reserved), `temp_file_limit=32MB`, no statement text in logs |
| login | `pg_hba.conf` has three rules: socket for the admin, `hostssl ipass_demo ipass_outside all scram-sha-256`, then `reject` for everything else. Static file, nothing rendered, so a subnet typo cannot open anything (review M1). The container's healthcheck refuses to be healthy unless the file parsed, has exactly 3 rules and ends in `reject`; `pg-front` starts only after pg-demo is healthy |
| role `ipass_outside` | LOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEDB/ROLE, NOREPLICATION, CONNECTION LIMIT 3 (shared by ALL users of the login), member of nothing, owns nothing, SELECT on the demo tables only, no CREATE on schema or database, no TEMPORARY, `pg_advisory_*` / `lo_*` / `loread` / `lowrite` revoked, default privileges give PUBLIC nothing. `outside-role.sql` ends with a DO block that fails the run if any of that is untrue |
| what exists | exactly the tables of `demo-columns.txt` (9 tables, named columns) + `demo_snapshot`. The live secret tables (`source_credential`, `target_credential`, ...), every credential/token/hash/settings/payload column, the `app_user` / `role_grant` tables, Keycloak data and tenant `showcase-b` are NOT in the copy: they do not exist there |

Why a copy and no RLS: the database holds one tenant's rows, so there is nothing to pin. The review's H3 (advisory locks, large objects, work_mem, lock queueing against the platform) no longer reaches the platform: whatever a hostile login does lands on pg-demo, which nobody else uses.

### Session defaults are not barriers
`statement_timeout 15s`, `idle_in_transaction_session_timeout 30s`, `idle_session_timeout 10min`, `lock_timeout 2s`, `default_transaction_read_only`, `work_mem 2MB` are role defaults a client can lift with `SET`. `temp_file_limit` cannot be lifted (superuser-only). The real barriers are the grants (no write, no create, no secret to read), the connection limit, the proxy, and the container caps.

### Resource caps, said plainly
- Memory 256 MB with no swap; CPU 0.25; pids 100: **hard** (Docker). A client that raises `work_mem` and sorts in 3 sessions can get pg-demo OOM-killed; it restarts (`restart: unless-stopped`) and only the demo is down.
- Connections: 15 total, 12 for logins, role limit 3, proxy 12 in total and 3 per address.
- Disk: **Docker cannot cap the size of a named volume on the default local driver, so there is no hard disk cap.** The disk is bounded structurally instead: the outside role can write nothing (no table or schema privilege, no temp tables, no large objects), the data set is a few MB, temp files are limited to 32 MB per session (about 96 MB at three sessions), WAL is limited by `max_wal_size=128MB`. Logs go to journald (30-day retention) at one line per connection, bounded by the throttle. A hard cap needs an xfs project quota on `/var/lib/docker` or a tmpfs-backed volume (data lost at restart); neither is done here.

## 2. The demo data (`demo-columns.txt`, `demo-columns.sql`, `demo-refresh.sh`)

Route chosen: a filtered `COPY` of an explicit column allow-list, not `seed.sql`, not `pg_dump`/restore.
- `seed/seed.sql` only creates tenants and users; the interesting data (systems, endpoints, interfaces, routes, messages, deliveries) is made through the app and would not be in it.
- A dump carries the credential tables and would have to delete them afterwards: the secret would exist in the dump. With the allow-list a secret column is never selected, and other tenants are excluded by the `WHERE` on the live side, so `showcase-b` rows never leave the live database.
- The `tenant` row is `id, code, status, time_zone`. No user table content is copied (no issuer/subject either; the demo does not need it).

Refresh procedure (on the server, in `/docker/ipass`, any time, repeatable):
`./outside/demo-refresh.sh showcase-a`
It reads the live database through its container socket in a READ-ONLY session (`default_transaction_read_only=on`, `COPY ... TO STDOUT`; the live database is never written), builds one SQL file in a private temp directory, loads it into pg-demo in ONE transaction (drop, create, copy, grant, secret-column check) and deletes the file. A live schema change that removes a listed column makes it stop with "nothing was changed". **Outside users see a snapshot, not live data**: it does not move until the next refresh; `select taken_at, tenant_code from demo_snapshot` says when. Sessions open during a refresh see the old copy until commit. Before the first refresh, eyeball that nothing real is typed into `showcase-a`: `business_key` and `target_endpoint.base_url` are free text and are copied.

## 3. Certificate (pinned, self-signed; decision of Dipesh)

`./outside/make-tls-cert.sh ./outside/tls <VPS-IPv4>,127.0.0.1` on the server (127.0.0.1 so the pre-firewall check in step 12 can use verify-full). ECDSA P-256, **validity 1095 days (3 years)**; the script refuses less than 365 or more than 1100. `CA:FALSE`, `keyUsage digitalSignature`, `extendedKeyUsage serverAuth`, SAN = the IP (no DNS change). `outside/tls/` is git-ignored. The script prints the **SHA-256 fingerprint and expiry**.
- **Fingerprint:** send it to each user over a DIFFERENT channel than the certificate file and than the password (for example the file by mail, the fingerprint by phone/chat); the user compares `openssl x509 -noout -fingerprint -sha256 -in server.crt` with it before first use. A client that cannot do `verify-full` gets no access.
- **Key handling:** the key stays on the host, mode 0600 root, directory 0750. The container copies it into a private tmpfs with owner `postgres` and mode 0600 at start, so host-side ownership cannot break TLS (review H1) - a rootless-Docker host needs a check that the container's root can still read `./tls`.
- **Expiry alert:** `./outside/cert-expiry-check.sh /docker/ipass/outside/tls/server.crt [warn-days, default 90]` exits 1 and logs at `crit` priority to the host log when the certificate expires within the window. Run it daily from cron/systemd and wire its failure into the monitoring Dipesh already reads; this repo has no alert channel of its own (limit).
- **Renewal (about 3 months before expiry):** (1) make a new pair in a new directory: `./outside/make-tls-cert.sh ./outside/tls-new <VPS-IPv4>,127.0.0.1`; (2) send the new `server.crt` and its fingerprint to every user by two channels and agree a cut-over day; (3) on that day `mv outside/tls outside/tls-old && mv outside/tls-new outside/tls` and `docker compose --env-file .env -f outside/compose.outside.yaml restart pg-demo` (about 15 s of downtime), (4) users replace their pinned file; (5) after a week delete `tls-old`. The certificate is bound to the IP: a changed VPS address means a new certificate.
- Client tools: `sslmode=verify-full` with `sslrootcert` works in psql/libpq, pgjdbc and DBeaver; ODBC drivers differ. Not run here (NOT VERIFIED with a real client other than psql 17).

## 4. Secrets and the exact client line

`./outside/outside-role.sh env` generates `DEMO_ADMIN_PASSWORD` (never usable from the network: `pg_hba.conf` has no network rule for the admin) and `OUTSIDE_DB_PASSWORD` into `/docker/ipass/.env` (mode 600, 48 characters) and prints only 8-character fingerprints. `up` applies the password to pg-demo through an environment variable and psql `\getenv`, so it is never on a command line (`exec -e OUT_PW` carries the name only), never in script output, and never in a log: pg-demo runs with `log_min_error_statement=panic` and `log_statement=none`, and the SQL sets the same locally. `./outside/outside-role.sh rotate` makes a new password, applies it, then writes `.env`.

**Dipesh retrieves the password** with (it prints to his own terminal only):
`ssh <vps> "sudo sed -n 's/^OUTSIDE_DB_PASSWORD=//p' /docker/ipass/.env"`
He stores it in a password manager and gives it to a user through the OS store of the user's machine, never chat or mail.

**Client connection line** (`<VPS-IPv4>` and the file path are the only things to fill in; the password comes from the prompt, `PGPASSWORD` or `~/.pgpass`, not from the line):
```
psql "host=<VPS-IPv4> port=5432 dbname=ipass_demo user=ipass_outside sslmode=verify-full sslrootcert=/path/to/server.crt"
postgresql://ipass_outside@<VPS-IPv4>:5432/ipass_demo?sslmode=verify-full&sslrootcert=/path/to/server.crt
jdbc:postgresql://<VPS-IPv4>:5432/ipass_demo?sslmode=verify-full&sslrootcert=/path/to/server.crt
```
Optional second guard for libpq clients: add `channel_binding=require` (not verified here). `sslmode=require` without the pinned file is encryption without server authentication: do not use it.

## 5. Throttle on the host (`pg-throttle.sh`) - exact rules

The review's H2 is fixed by scope: the rule matches only NEW TCP connections that ENTER THE HOST on the external interface and were addressed to port 5432. Container-to-container traffic (app to live Postgres, backup, Keycloak) arrives on a `br-*` bridge, never on the external interface, so it is never counted or dropped. The external interface is the default-route interface (`ip -4 route show default`), or `EXT_IF=eth0 sudo ./outside/pg-throttle.sh on`.
```
iptables -N IPASS-PG
iptables -A IPASS-PG -m connlimit --connlimit-above 3 --connlimit-mask 32 -j DROP
iptables -A IPASS-PG -m hashlimit --hashlimit-name ipass-pg --hashlimit-mode srcip --hashlimit-above 12/minute --hashlimit-burst 6 -j DROP
iptables -A IPASS-PG -j RETURN
iptables -I DOCKER-USER 1 -i <ext_if> -p tcp -m conntrack --ctstate NEW --ctorigdstport 5432 -j IPASS-PG
iptables -I DOCKER-USER 1 -i br-ipass-pgpub -m conntrack --ctstate NEW -j DROP      # the proxy cannot open a connection to anywhere (review L2)
```
Check on the host (`iptables -C` exits 0 when the rule exists): `sudo ./outside/pg-throttle.sh check` runs
`iptables -C DOCKER-USER -i <ext_if> -p tcp -m conntrack --ctstate NEW --ctorigdstport 5432 -j IPASS-PG`, `iptables -C DOCKER-USER -i br-ipass-pgpub -m conntrack --ctstate NEW -j DROP`, `iptables -C IPASS-PG -m connlimit --connlimit-above 3 --connlimit-mask 32 -j DROP` and `iptables -C IPASS-PG -j RETURN`, and exits 1 if one is missing. Counters: `sudo ./outside/pg-throttle.sh status`.
Remove (rollback; same as `pg-throttle.sh off`):
```
iptables -D DOCKER-USER -i <ext_if> -p tcp -m conntrack --ctstate NEW --ctorigdstport 5432 -j IPASS-PG
iptables -D DOCKER-USER -i br-ipass-pgpub -m conntrack --ctstate NEW -j DROP
iptables -F IPASS-PG; iptables -X IPASS-PG
```
Persistence: rules live in the kernel until reboot. A systemd oneshot (`After=docker.service`, `ExecStart=/docker/ipass/outside/pg-throttle.sh on`, `ExecStop=... off`, `RemainAfterExit=yes`) re-applies them; after any `systemctl restart docker` run `check`. Rollback deletes the unit file, not just disables it.

### Capacity and residual risk (re-derived)
- pg-demo: 15 connections, 3 reserved for the admin, **12 for logins**. The role limit is **3 simultaneous sessions in total, shared by every user of the login** (one credential, one limit).
- Proxy: 12 connections in total, 3 per address, 10 new per 10 s per address. Kernel: 3 per address, 12 new per minute per address (burst 6).
- A wrong-password or half-open connection occupies a slot until it fails or `authentication_timeout` (10 s). Sustained, one address can keep about 2 slots busy at the kernel's 12 new/min (12/60 per second x 10 s), 3 in a short burst. So **about 4 addresses can close the door for a few seconds and about 6 can keep it closed**; 7 or more can do it at will. Real users are then refused or queued until the attack stops. Only the demo door is affected: the live platform shares no listener, no memory, no connection slot and no disk with it.
- Brute force: scram-sha-256 on a 48-character random password; 12 attempts per minute per address.
- Pre-auth cost: each unauthenticated TLS handshake costs CPU of the 0.25-CPU container only.
- Not defended (accepted for demo data): a user with the password can keep all 3 sessions busy; a hostile user can run heavy queries until the 15 s statement timeout (liftable) and the container caps stop them; any IP can attempt a login. The data is a snapshot of demo rows, so a leak is limited to those rows.
- Not testable on the build PC: the iptables rules and `enable_ip_masquerade=false` (checked only as dry-run text and by the host steps below).

## 6. Apply plan (phase 2b - not before the re-review and Dipesh's approval; on the VPS in /docker/ipass)

Order matters: **everything is verified from the host BEFORE the provider firewall is opened.** No step touches the live containers.
0. Read-only checks: memory and disk fit (README fit rule; the new stack adds about 320 MB of limits); host port free (`ss -ltn 'sport = :5432'` prints nothing); `iptables -V` and that `DOCKER-USER` exists; `docker images` has `postgres:17.11-alpine` and `haproxy:3.0.11-alpine` (no new image, no new volume besides `ipass-outside_pg-demo-data`).
1. `cp .env /docker/ipass-backups/<ts>-env` (the stack is not changed, so no dump is needed for this step).
2. Copy `outside/` from the merged branch to `/docker/ipass/outside/`; `chmod +x outside/*.sh`.
3. `./outside/make-tls-cert.sh ./outside/tls <VPS-IPv4>,127.0.0.1` (as root). Write down the fingerprint.
4. `./outside/outside-role.sh env`; then `F="--env-file .env -f outside/compose.outside.yaml"; docker compose $F config -q`.
5. `docker compose $F up -d pg-demo`; wait until `docker compose $F ps` shows `healthy` (about 30 s; healthy includes the pg_hba self-check, fail closed). Not healthy: go to rollback R2.
6. `./outside/demo-refresh.sh showcase-a`, then `./outside/outside-role.sh up`.
7. `docker compose $F up -d pg-front` (starts only after step 5's healthy). Check `ss -ltn 'sport = :5432'` shows IPv4 only; `docker exec <pg-front> wget -T3 -qO- http://1.1.1.1` must FAIL.
8. `sudo ./outside/pg-throttle.sh on`, then `sudo ./outside/pg-throttle.sh check` (all OK); install and enable the systemd unit of section 5. Check the platform is untouched and healthy: `docker compose ps` (live project), `https://ipass.<domain>` 200, `https://auth.<domain>/realms/platform/account` 200, and `docker compose exec platform-app ...` health as in the README (the throttle must not have changed anything for internal traffic).
9. **Verify before opening the firewall:** `OUTSIDE_PW="$(sed -n 's/^OUTSIDE_DB_PASSWORD=//p' .env)" ./outside/verify.sh 127.0.0.1 ./outside/tls/server.crt host` must end `VERIFY: ALL PASSED` (TLS, plain refused, every other role and database refused before any password check, read-only, no secret table, advisory/lo not callable, timeouts, big sort stopped, connection limit, flood throttled). Any FAIL: do not continue, go to rollback.
10. Open the provider firewall: add an inbound TCP 5432 rule (IPv4, any source) to the VPS firewall group and wait for `synced`. This is the one outward change and needs Dipesh's approval at the permission prompt.
11. From another machine: `OUTSIDE_PW=... ./outside/verify.sh <VPS-IPv4> server.crt bridge` (or the manual list in section 8). Then hand the certificate file, the fingerprint (second channel) and the password (third, OS store) to the user.
12. Optional, not part of apply: a cron entry for `demo-refresh.sh` and one for `cert-expiry-check.sh`.

## 7. Rollback (in this order; each step is safe to repeat). The live stack is unchanged by rollback because it was unchanged by apply.

R1. Remove the provider firewall rule for 5432; wait for `synced`.
R2. `docker compose --env-file .env -f outside/compose.outside.yaml down -v` : stops and removes `pg-front` and `pg-demo`, both networks and the demo volume.
R3. `sudo ./outside/pg-throttle.sh off` (removes both DOCKER-USER rules and the chain); `systemctl disable --now` the unit and delete its file.
R4. `rm -rf outside/tls`; remove the `DEMO_ADMIN_PASSWORD` and `OUTSIDE_DB_PASSWORD` lines from `.env`; optionally delete `outside/`.
Verify: `ss -ltn` shows no 5432, `nc -vz <VPS-IPv4> 5432` times out, `docker volume ls | grep pg-demo` is empty, `iptables -S DOCKER-USER` has no IPASS-PG / br-ipass-pgpub rule, the three public links answer as before.

## 8. Verification list (manual form of `verify.sh`; `H` = host, `PW` = outside password, `CRT` = pinned server.crt)

1. TLS: `PGPASSWORD=$PW psql "host=H dbname=ipass_demo user=ipass_outside sslmode=verify-full sslrootcert=CRT" -c "select ssl, version from pg_stat_ssl where pid = pg_backend_pid()"` is `t`, TLS 1.2/1.3.
2. Plain refused: `sslmode=disable` gives `pg_hba.conf rejects connection`.
3. Other roles (`ipass_demo_admin`, `ipass_admin`, `ipass_backup`, `keycloak`, `postgres`) and other databases (`ipass`, `keycloak`, `postgres`, `template1`) give `pg_hba.conf rejects connection`, before any password check.
4. `\dt` shows only the demo tables; `select * from source_credential` says the table does not exist; each of insert/update/delete/truncate/create table/create temp table/drop gives `permission denied` even after `begin read write`.
5. `select pg_advisory_lock(1)`, `select lo_create(0)`, `select lowrite(0,'x')` give `permission denied`; `set temp_file_limit='1GB'` gives `permission denied`; `select pg_sleep(20)` is cancelled at 15 s.
6. Throttle: 4 parallel sessions, the 4th refused; 25 wrong passwords in 10 s from one address, most cut at connect; `pg-throttle.sh status` shows drop counters.
7. Surface: an IPv4 scan shows only 22, 80, 443, 5432; `nc -6 -vz H 5432` fails.
8. After rollback: `nc -vz H 5432` times out or is refused.

## 9. Review findings: where each went

H1 key/TLS dir: fixed by the key-copy wrapper + script-made dir used as the bind mount in the test. H2 throttle scope: section 5. H3: removed by option A. M1: static 3-rule hba, healthcheck self-check, verify-before-firewall order. M2: numbers re-derived in section 5 (the live `max_connections` is untouched, so the 27-vs-30 headroom question is gone). M3: section 3. M4: section 4. M5: `outside/tls/` git-ignored; the rendered hba no longer exists. L1 accepted (demo copy; check the free-text columns once). L2 done (egress drop). L3 open: Postgres logs show the proxy address, real client addresses exist only in the HAProxy log (`docker logs ipass-outside-pg-front-1`, journald); correlate by time. L4 done in spirit (the role SQL checks all relations and functions, not a fixed list). L5 and L6 moot (the live database and its backups hold nothing of this change; rollback is `down -v`). L7: the connection-limit probe in `verify.sh` still accepts several wordings (`too many connections|server closed|...`): loose, kept because the refusal can come from HAProxy or Postgres.

## 10. Known limits

- Any-IP exposure remains an attack surface (section 5). IPv4 only. Self-signed certificate (distribution and renewal are manual). One shared login for all outside users.
- Client-side session defaults can be changed by the client; the grants and the container caps are the real barriers. No hard disk cap (section 1).
- The copy is a snapshot; it is only as fresh as the last `demo-refresh.sh`.
- Not verified without a live Linux host: the DOCKER-USER rules, `enable_ip_masquerade=false`, the key-copy wrapper under the host's Docker (rootless included), `channel_binding=require`, clients other than psql.
