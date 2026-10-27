# iPass showcase on the Hostinger VPS - runbook

Source: ADR-H-IPASS-01 (hosting decision; kept in the planning workspace, not in this repository). This is a showcase/staging copy with fictional data only. `${IPASS_DOMAIN}` is a placeholder until the domain is named; hostnames are `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}`. No secret appears in this file or in git. Every production step below needs the project owner's approval at the permission prompt.

**The whole picture** (links, what is not public and how to reach it, services and image pins, redeploy and rollback by backup directory, the ops address and operator accounts, security status and known gaps) is in [`documents/deployment.md`](../../documents/deployment.md). This README is the operator quick-start: file list, first-install runbook, update, rotation, tests.

## What is in this folder

| File | Purpose |
|---|---|
| `compose.yaml` | The production stack: `edge` (the only service on the shared `web` network), app, ui, keycloak, postgres, kafka (+ `kafka-init`), `kafbat` (read-only operator view, T219), `pg-backup`, one-shot `pg-restore-test`. No test tooling |
| `edge/edge.conf` | The edge proxy path allow-list. Since 27-Oct (row 489) it routes actuator, `/v3`, `/swagger-ui`, all of Keycloak (admin console and master realm too) and, on ops, the WireMock admin `/__admin/`; see "Live state" below. Login and token paths are rate limited: about 10 requests/min per client, burst 5, 429 beyond that (the client is the last `X-Forwarded-For` entry, the one Traefik appends - a spoofed first entry is ignored). |
| `test/edge-ratelimit.sh` | Behaviour proof of that rate limit in nginx (build PC, Docker lock) |
| `test/edge-ops.sh`, `test/ops-setup-stub.sh`, `test/kcadm-client-hosts-stub.sh`, `test/kcadm-client-setup-stub.sh` | Behaviour proof of the ops and auth edge rules in nginx, and of `ops-setup-kcadm.sh` against a stub kcadm |
| `test/public-smoke.sh` | Public smoke from outside the server (curl + bash, one python3 snippet for the TCP checks): site, actuator closed, Keycloak, ops edge, Postgres TLS 1.3 fingerprint, Kafka Metadata. `bash deploy/hostinger/test/public-smoke.sh [fingerprint]`; no Docker, no lock, no logins |
| `demo/compose.demo.yaml` | Opt-in override: WireMock demo destination + the showcase profile. Never part of `compose.yaml` |
| `keycloak/` | `compose.bootstrap-admin.yaml` (first start only), `retire-bootstrap-admin.sh`, `kcadm-client-setup.sh` + `kcadm-client-setup-kcadm.sh` (master service-account client `ipass-kcadm` for the scripts, row 543), `kc-login.sh`, `Dockerfile`; `ops-setup.sh` + `ops-setup-kcadm.sh` (role `ops-operator`, client `ops-kafbat`, flow `ops-browser`; safe to re-run) |
| `kafbat/kafbat-roles.yml` | The Kafbat RBAC role: view-only, no message permission |
| `rotate-secret.sh` | Rotates one secret at a time (database roles, realm client secrets) |
| `env.example` | Variable NAMES only |
| `bootstrap-env.sh` | Generates every secret on the server into a root-only `.env` (and the Keycloak bootstrap admin into `.env.bootstrap-admin`); prints fingerprints only; never overwrites |
| `postgres-init/` | `10-keycloak-database.sh`, `20-platform-roles.sh` (the four DB roles + `ipass_backup` + `ipass_restore`) |
| `kafka/create-topics.sh` | Creates the three platform topics |
| `backup/` | `backup.sh` (nightly dump loop), `restore-test.sh` |
| `flyway/`, `deploy.sh` | Standalone Flyway CLI migration pipeline: `Dockerfile` (+ `flyway.toml`), `migrate.sh` (info / validate / migrate / repair --yes), `check-migration-dirs.sh` (build guard); `deploy.sh` = backup -> migrate -> switch tag -> health. See "Migration pipeline" below |
| `seed/` | `seed-showcase.sh` + `seed.sql` (two tenants, four users); `NESTED-APPOINTMENT.md` = hand-off for the nested-appointment showcase flow (not part of the seed run) |
| `test/` | `test-scripts.sh` (offline), `smoke.sh` + `compose.smoke.yaml` (build-PC only) |
| `kafka-public/` | `compose.kafka-public.yaml` (overlay: `kafka-front` HAProxy publishes host 9092), `haproxy.cfg`, `kafka-public-fw.sh on\|off\|check` (outbound-drop rule), `ipass-kafka-fw.service` (re-applies it at boot) |
| `outside/` | The demo Postgres door as its own compose project `ipass-outside` (`compose.outside.yaml`, host 5432); `pg-throttle.sh on\|off\|status\|check`, `ipass-pg-throttle.service` (re-applies it at boot), `OUTSIDE-ACCESS.md` (design, apply, rollback) |

Images (built elsewhere, see "Build"): `<registry>/ipass/platform-app:<sc>`, `.../ui:<sc>`, `.../keycloak:26.8.1-<sc>`. `<sc>` = short commit of the merged `main`. `<registry>` is your container registry host. `compose.yaml` still carries a built-in default registry prefix; `IPASS_IMAGE_PREFIX` (the prefix) or `IPASS_APP_IMAGE` / `IPASS_UI_IMAGE` / `IPASS_KEYCLOAK_IMAGE` (whole names) override it. Never `latest`.

The compose `image:` names are overridable per service: `IPASS_APP_IMAGE`, `IPASS_UI_IMAGE`, `IPASS_KEYCLOAK_IMAGE` (full `repo:tag`). Each defaults to the prefix form (the built-in default prefix in `compose.yaml`, overridden by `IPASS_IMAGE_PREFIX`), so the existing `IPASS_IMAGE_PREFIX=<registry>/ipass` setup is unchanged. Docker Hub has one namespace level, so the same tag as in the registry cannot be built from one prefix - to serve the published Hub images set, e.g. `IPASS_APP_IMAGE=dipeshpansuriya/ipass-platform-app:3105c3d3`, `IPASS_UI_IMAGE=dipeshpansuriya/ipass-ui:3105c3d3`, `IPASS_KEYCLOAK_IMAGE=dipeshpansuriya/ipass-keycloak:26.8.1-<sc>`. Never `latest`.

## Build (build PC; step 6 of the ADR; an outward push needs the project owner's approval)

```
# Gate first: run the tests on the SHA you build (JAVA_HOME=<jdk25> ./mvnw -B -o test), or cite a green SHA whose
# `git diff --name-only <green-sha> <deploy-sha>` touches only deploy/ scripts or docs. Never package untested app code.
JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests   # only after the gate above is met
(cd ui && npm ci && npx vite build)
docker build -f platform-app/Dockerfile -t <registry>/ipass/platform-app:<sc> .
docker build -f ui/Dockerfile -t <registry>/ipass/ui:<sc> .
docker build -f deploy/hostinger/keycloak/Dockerfile -t <registry>/ipass/keycloak:26.8.1-<sc> .
```
Scan the source tree and the three images (gitleaks, plus a vulnerability scan; Critical/High = no push) before any push.

## Runbook (on the VPS, over SSH)

0. **Fit rule first** (ADR Decision 2): `MemAvailable >= 4.5 GB`, free disk `>= 25 GB` on the Docker data root, `>= 2` vCPU with 15-minute load below 50 %, Traefik present on the `web` network. Fail any one: stop and report to the project owner (new VPS = the project owner's spend).
1. **DNS**: A records `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}` to the VPS IPv4 (and AAAA if the VPS has IPv6). Wait until both resolve.
2. **Firewall**: the provider firewall group allows TCP 22, 80, 443, 5432 and 9092 (5432 and 9092 only once the demo Postgres door and the Kafka front are applied, see "Live state"), shows `synced`. Before those two overlays: 22, 80, 443 only.
3. **Log retention**: `/etc/systemd/journald.conf.d/ipass.conf` with `MaxRetentionSec=30day` and `SystemMaxUse=2G`; `systemctl restart systemd-journald`. Check no other stack relies on longer retention (ADR Decision 6).
4. **Project folder**: `mkdir -p /docker/ipass && cd /docker/ipass`; copy this `deploy/hostinger/` folder's contents here (`scp -r`), then `chmod +x *.sh */*.sh`. (The postgres init scripts must be executable.)
5. **Secrets**: `IPASS_DOMAIN=<domain> IMAGE_TAG=<sc> ./bootstrap-env.sh` writes `.env` (mode 600) and prints fingerprints. The project owner keeps a copy of `PLATFORM_CREDENTIAL_KEK_K1` in his password manager (losing it makes sealed credentials unreadable). The temporary Keycloak admin is written to its own file `.env.bootstrap-admin` (mode 600), not to `.env`.
   - Demo destination (optional): add `-f demo/compose.demo.yaml` to every compose command below; it brings WireMock and sets `SPRING_PROFILES_ACTIVE=showcase`, the one allow-listed host and the showcase-only exception. Without it none of that exists.
6. **Registry**: `docker login <registry>` with the pull-only token (the project owner creates it; `--password-stdin`).
7. **Validate**: `./check-issuer-override.sh` (fails if `OIDC_ISSUER_URI` is set in the env file without `KEYCLOAK_ADMIN_BASE_URI`) and `docker compose -f compose.yaml -f keycloak/compose.bootstrap-admin.yaml config -q` (must print nothing).
8. **Start** (first time, with the bootstrap admin): `F="-f compose.yaml -f keycloak/compose.bootstrap-admin.yaml"` (append `-f demo/compose.demo.yaml` for the demo), then `docker compose $F pull && docker compose $F up -d`. After step 11 the `keycloak/compose.bootstrap-admin.yaml` file is no longer used. First start: Postgres runs the init scripts (roles), Keycloak imports realm `platform`, `kafka-init` creates the topics, the app runs Flyway and starts.
9. **Wait and verify** (guide section 8): all containers `Up` and healthy, restart count not rising for 90 s; `docker compose logs platform-app` shows a clean start.
10. **Seed**: `./seed/seed-showcase.sh` (creates the four realm users with the generated showcase passwords, binds tenants and roles). Hand the showcase passwords to the project owner through the OS store, never through chat.
11. **Keycloak admin**: create a named master-realm admin with `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...` over SSH, then (before that admin enrols TOTP) create the kcadm service-account client once: `./keycloak/kcadm-client-setup.sh --with-master-users` (row 543; creates master client `ipass-kcadm`, client credentials only, secret from your password manager), then `KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh` (logs in with that client; the secret comes from a silent prompt, never typed inline; it first proves the named admin exists, is enabled and has the admin role, and refuses otherwise; the old named-admin password login is the explicit fallback `KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh --password-login`; it deletes the `bootstrap-admin` user and `.env.bootstrap-admin`, and re-creates keycloak from `compose.yaml` alone, so the credential is no longer in the container environment). Order, all BEFORE the named admin enrols TOTP (the setup script logs in with the admin password): create with `--with-master-users`, retire, re-run `./keycloak/kcadm-client-setup.sh` WITHOUT the flag to drop its master-realm roles, rotate the client secret (`--rotate`; the old one had master power), then TOTP; after TOTP both jobs are console work (runbook row 543, section 4). Later seeding or client-secret rotation uses the same client. A named admin with TOTP cannot be used by kcadm (no one-time-code field). History: until 27-Oct `/admin` and the `master` realm were not routed publicly; since row 489 chunk C (decision of 27-Oct) `auth.<domain>` serves all of Keycloak, so the named admin should enrol a one-time code as soon as T220 allows (see "Live state").
12. **Outside checks** (ADR conformance C2/C3): anonymous `GET https://ipass.${IPASS_DOMAIN}/api/v1/...` is 401; `https://ipass.${IPASS_DOMAIN}/actuator/health` answers and any other `/actuator` path is refused by the app (the edge passes `/actuator`, `/v3`, `/swagger-ui` on since 27-Oct; the app decides, `edge/edge.conf:139-144`); `https://auth.${IPASS_DOMAIN}/admin/` now answers (Keycloak login) by decision; ports 22/80/443 answer, plus 5432 and 9092 when the two overlays are applied (IPv4 only for those two).
13. **Facts to verify on the host** (ADR V5/V6/V8): `docker stats --no-stream` after one demo run (alarm above 80 % of a limit); the app can fetch `https://auth.${IPASS_DOMAIN}/realms/platform/.well-known/openid-configuration` from inside its container - if not, add `extra_hosts: ["auth.${IPASS_DOMAIN}:host-gateway"]` to `platform-app`; IPv6 is covered by the firewall.
14. **Backup**: `docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh` (also before every change; exit code non-zero and a `backup FAILED` line if either dump failed or is unreadable - a failed dump is deleted and nothing is pruned), then **restore test** `docker compose --profile restore-test run --rm pg-restore-test` (expect `OK tenant`, `OK audit_event`; it runs as `ipass_restore`, a one-shot container that exists only for the test). Repeat monthly. Nightly dumps run at 02:00 UTC into volume `ipass-backups`, 7 daily + 4 weekly.

## Update and roll back

1. `cp -p .env .env.bak-$(date -u +%Y%m%dT%H%M%SZ)` and the same for `compose.yaml`; run a manual dump (step 14).
2. Change the `IMAGE_TAG` line only; `docker compose pull && docker compose up -d` for this project only.
3. Verify as in steps 9 and 12.
3a. After changing `edge/edge.conf` (or `kafbat/kafbat-roles.yml`), **force-recreate** that service (`up -d --force-recreate --no-deps edge`): they are single-file bind mounts, a reload keeps the old file.
3b. A save refused with "the host is not on the destination allow-list" means the destination is not in `IPASS_DESTINATION_HOSTS` (`.env`): add the host, then `docker compose up -d platform-app` (a restart is needed: the list is read at start-up and no console screen edits it). See [`documents/deployment.md`](../../documents/deployment.md) section 3.1.
4. **Roll back**: restore the backup files and the previous tag, `docker compose up -d` once. A release whose Flyway migration already ran cannot roll back the schema: restore the pre-change dump instead. If the same step fails twice, stop and find out why before a third try.

## Rotation

One secret at a time, never a blanket regeneration (`bootstrap-env.sh` refuses to overwrite and has no `FORCE`: regenerating everything would orphan the database roles, the imported realm and the sealed credentials). In `/docker/ipass`:

- Database passwords: `./rotate-secret.sh DB_PASSWORD` (also `DB_MIGRATION_PASSWORD`, `BACKUP_DB_PASSWORD`, `RESTORE_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `POSTGRES_PASSWORD`). It runs `ALTER ROLE` first, writes `.env` only after that worked, re-creates the dependent service, prints an 8-character fingerprint and appends it (no value) to `.rotation-log`. A later plain `up -d` may restart postgres once (its environment holds the role passwords).
- Realm client secrets: `./rotate-secret.sh CONSOLE_OIDC_CLIENT_SECRET` (or `PLATFORM_ADMIN_CLIENT_SECRET`, `OPERATOR_AGENT_CLIENT_SECRET`); kcadm (logged in as the master client `ipass-kcadm`; secret from a silent prompt, never typed inline) sets the secret in the realm. Fallback: `KC_ADMIN_USER=<named admin> ./rotate-secret.sh --password-login NAME` (only while that admin has no TOTP).
- Showcase user passwords: edit the line in `.env`, re-run `./seed/seed-showcase.sh` (logs in with `ipass-kcadm` once the bootstrap admin is retired; `--password-login` is the fallback).
- `PLATFORM_CREDENTIAL_KEK_K1` is **not** rotated by the kit: a new key needs the old one kept until every sealed target credential is re-sealed (application-side step, not built). Keep the copy in the password manager.
- Schedule: rotate the database and client secrets every 90 days (the same cadence as the registry token); `.rotation-log` is the record. The registry pull token: create the second password, `docker login` with it, verify a pull, delete the first.

## Networks and the Docker socket

**Keycloak admin address.** The public auth host hides `/admin` and the `master` realm, so the app cannot create or disable machine clients through the public issuer URL (it would get 404, logged as `credential.source_refused ... reason=identity_provider_unavailable`, HTTP 503). `compose.yaml` therefore gives `platform-app` `KEYCLOAK_ADMIN_BASE_URI` (default `http://keycloak:8080`, the keycloak service on `ipass-front`/`ipass-data`; leave it unset in `.env`): the app sends only the admin REST calls (client create, lookup, disable) there, while tokens and the issuer stay `OIDC_ISSUER_URI` (the public `https://auth.<domain>/realms/platform`). Unset or empty in the app (any other deployment) = the admin URL is derived from the issuer, as before. The value must be a plain `http(s)://host[:port][/path]` address (no user info, query or fragment; trailing slashes are dropped) or the app refuses to start. **An override must be an internal address the edge does not publish** (a private Docker service name, loopback or RFC1918 address): the admin bearer token and the new client secret are sent over it, so a public or routed host would expose them. The app **refuses to start** (fail closed; the message names the setting, never the value) unless the value is `https`, or plain `http` to an internal host: a single-label service name (no dots, such as `keycloak`), a loopback address or an RFC1918 / ULA private address. Plain `http` to a public DNS name or public IP, user info, a query, a fragment, a port outside 1-65535 and an underscore in the host name are refused. **`KEYCLOAK_ADMIN_ALLOW_EXTERNAL`** (default off; leave it unset in `.env`) is the explicit opt-in for an `https` admin address on a host other than the issuer host, as an external identity provider needs; it never allows plain `http` to a public host. It is off by default because that setting sends the admin bearer token and the new client secret to a host outside the issuer, so it must be a deliberate choice. A single-label service name is resolved by the container DNS and its search domains, not by this check, so it must be a trusted internal service name (letters, digits and hyphens, starting with a letter; all-digit, hex and octal hosts such as `134744072` or `0x7f000001` are refused). The admin base is validated only when `PLATFORM_ADMIN_CLIENT_SECRET` is set (the admin beans are conditional on it); a deployment without that secret never reads the value. Not verified without a live run: that Keycloak accepts an admin call on the internal HTTP address with a token whose `iss` is the public URL (`KC_HOSTNAME` is the full public URL, so the issuer it expects is public; the realm's `sslRequired: external` allows plain HTTP from the private Docker network) - after the deploy, one real "issue source credential" check in the console settles it; if Keycloak refuses, the app fails closed (HTTP 503, `identity_provider_unavailable`), never open. **Overriding `OIDC_ISSUER_URI` to an external identity provider?** Then also set `KEYCLOAK_ADMIN_BASE_URI` to that provider's internal admin address: the default above points at the bundled Keycloak only, so admin calls would otherwise go to it with the wrong realm. `./check-issuer-override.sh` (run before `docker compose up`) fails in that case and prints variable names only. It reads the env file only, not the shell environment, so a variable exported in the shell is not seen; it accepts `export NAME=...`, leading spaces, CRLF endings and surrounding quotes, and treats an empty quoted value as unset.

Only `edge` joins the host's shared `web` network, so no other stack on that network can reach the app, the actuator or Keycloak directly. `platform-app` alone has outbound reach (`ipass-egress`) for allow-listed destinations and the public issuer URL; everything else sits on internal networks. No iPass service mounts the Docker socket (checked by `test-scripts.sh` and `smoke.sh`).

The host's Traefik does read the socket (ADR Decision 5 point 8). A read-only mount does not limit what a compromised Traefik can see or do through the socket API. Recommended (outside this stack, the operator and the project owner, not done by the kit): put a socket proxy in front of it that allows only `GET /containers` and `/events` (for example `tecnativa/docker-socket-proxy` with `CONTAINERS=1`, everything else `0`, on an internal network shared only with Traefik), point Traefik's `docker` provider at `tcp://socket-proxy:2375`, and remove the socket mount from Traefik. Pin the image by digest when you add it.

## Migration pipeline (Flyway CLI, run before the app is deployed)

Adds a separate, repeatable migration step. **The app is unchanged:** it still runs Flyway at its own start-up (`SPRING_FLYWAY_ENABLED` is not touched; turning it off is a later phase, only after several releases went through this pipeline). After the CLI migrated, the app's own Flyway finds nothing pending. Same engine version as the app (Spring Boot manages Flyway 12.4.0), same settings: user `ipass_migrator`, table `flyway_schema_history`, `validateOnMigrate`, init-sql `SET lock_timeout = '5s'` (`flyway/flyway.toml`; the app's are `platform-tenancy/.../tenancy-database.yml` and `platform-app/.../flyway-lock-timeout.yml`). No secret is in the image or in any file: the password comes from the server's `.env` through the container environment.

**Build (build PC, repo root).** The image carries every module's `src/main/resources/db/migration` folder (six today: tenancy, config, delivery, ingest, app, routing). The guard fails if a module has such a folder that `flyway/Dockerfile` does not COPY (a future module), or if two folders repeat a file name or a Flyway version:
```
sh deploy/hostinger/flyway/build-image.sh <sc> [<registry>/ipass/flyway-migrate]   # <sc> = first 7+ characters of HEAD, the same tag as the other images
docker push <registry>/ipass/flyway-migrate:<sc>          # or, without a registry: docker save <registry>/ipass/flyway-migrate:<sc> | ssh <vps> docker load
```
`build-image.sh` builds from `git archive HEAD` (never the working tree), refuses a tag that is not the start of HEAD, refuses uncommitted or untracked files in a migration folder or `flyway/`, runs the guard, labels the image `org.opencontainers.image.revision=<full sha>` and `ipass.flyway.manifest=<hash of the migration files>`, and prints the image id (note it in the release note). `deploy.sh` / `migrate.sh` refuse (exit 4) an image whose revision label does not start with the release tag, before the database password is used, so a stale local image or a rebuilt tag is never run; `FLYWAY_IMAGE_ID=<id>` additionally pins the exact build. The name follows `${IPASS_IMAGE_PREFIX:-acsnewacr.azurecr.io/ipass}/flyway-migrate:${IMAGE_TAG}`; `FLYWAY_IMAGE` overrides the whole name. Pushing is the project owner's step.

**On the VPS (`/docker/ipass`).** Copy `flyway/migrate.sh` and `deploy.sh` next to `compose.yaml` (`scp -r deploy/hostinger/flyway deploy/hostinger/deploy.sh <vps>:/docker/ipass/`, then `chmod +x deploy.sh flyway/*.sh`). `IMAGE_TAG` is the commit being released; a value you pass wins over `.env` (as with compose). The `.env` is read, never sourced.
```
IMAGE_TAG=<sc> ./flyway/migrate.sh info        # applied / pending, read-only
IMAGE_TAG=<sc> ./flyway/migrate.sh validate    # history vs files (checksums, missing files); pending is not an error; read-only
IMAGE_TAG=<sc> ./flyway/migrate.sh migrate     # runs validate first and refuses when it fails, then applies the pending migrations
IMAGE_TAG=<sc> ./flyway/migrate.sh repair --yes   # rewrites flyway_schema_history; only after a backup and a look at `info`
IMAGE_TAG=<sc> ./deploy.sh                     # the whole release: backup, info, validate+migrate, tag switch, up -d, health wait, info
```
`deploy.sh` stops before touching anything if the backup or the Flyway image is missing, and again if `validate` or `migrate` fails (exit 5, "MIGRATION FAILED - do not deploy"); a failing `docker compose up -d` or a service that is not healthy after `HEALTH_TIMEOUT` (300 s) makes it exit 6 with the roll-back steps (after a failed `up -d` the env file is put back on the previous tag, but `up -d` recreates services one by one, so some may already run the new tag: the script prints `compose ps` and warns against a plain `up -d` until you decide). There is no explicit `compose pull`: `up -d` pulls what is missing, and an image brought in with `docker save | docker load` exists only locally, where a pull would fail. It records the previous tag as `OLD_TAG`, keeps `.env` and `compose.yaml` in `release-backups/<utc>/`, and takes `COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml` when the demo overlay is in use. Exit codes are in the script header.

**Hardening (row 538).** The Flyway container is started in ONE place, `flyway/flyway-run.sh` (used by `migrate.sh`, so by `deploy.sh`, and by the proof): the migrator password goes into a mode-600 TOML file in a mode-700 temp directory, mounted read-only and passed with `-configFiles` - it is not in `docker inspect` (no `FLYWAY_PASSWORD` variable), not in the host process list, and the directory is removed when the container exits (also on failure or Ctrl-C). Flags: `--read-only` with `--tmpfs /tmp`, `--cap-drop ALL`, `--security-opt no-new-privileges`, `--pids-limit 256`, `--memory 768m`, `--user <your uid:gid>` (a root caller, i.e. `deploy.sh` on the VPS, keeps the file under the root-owned `/run/ipass-flyway` and runs the container as uid 61001, which is in no passwd file; the directory and the one file are chowned to it, no `-R`). `FW_DOCKER_ARGS`, `FW_RUN_USER` and `FW_SECRET_BASE` are tests only (refused unless `IPASS_FLYWAY_TEST=1`; uid 0 never). `deploy.sh` needs a lowercase hex `IMAGE_TAG` of 7+ characters; a standalone `migrate.sh` with `FLYWAY_IMAGE` set and no `FW_EXPECT_REVISION` prints a loud "image revision NOT verified" warning. `bash deploy/hostinger/test/flyway-run-linux.sh` proves the file mode, owner, chown, cleanup (also on SIGTERM) and argument list on real Linux, as root and as uid 54321, with a stub docker, `HOME=/tmp`. `deploy.sh` keeps `release-backups/` mode 700 with `.env.bak` / `compose.yaml.bak` mode 600, keeps only the newest `RELEASE_BACKUPS_KEEP` (default 5) release folders and logs each one it removes (the current release is never removed); `rotate-secret.sh` warns that older `.env.bak` copies still hold the rotated value. `deploy.sh` also takes a run lock (`.deploy.lock` holding the pid; a second run exits 2, a lock whose pid is gone is taken over), rewrites `.env` atomically (temp file in the same folder, `mv`), and after a failed `up -d` prints `compose ps` and warns that some services may already run the new tag.

**Two limits to know before a release.** (1) The old app keeps serving while `migrate` runs (backup, info, validate, migrate all happen before `up -d`), so every migration must work with the PREVIOUS app version as well: add columns and tables first, drop or rename in a later release. (2) The dump is taken at the start of `deploy.sh`; restoring it after a failed health check loses every write made since the dump. If a migration is destructive, run `docker compose stop platform-app` before `deploy.sh` so nothing is written after the dump.

**Settings that differ from what you might expect.** `IPAAS_FLYWAY_LOCK_TIMEOUT` (the app's variable, default 5s) is honoured by `migrate.sh` too, from the environment or the env file; anything but a plain Postgres duration (`5s`, `500ms`, `1min`, `0`) is refused. `migrate.sh` reads the env file line by line, not the way compose does, so a `DB_MIGRATION_PASSWORD` containing `$` or ` #` (which compose interpolates or cuts) is refused with a message; `bootstrap-env.sh` generates neither. `validate` ignores `pending` and `future` migrations (Flyway's default `*:future` is kept), so a database that is ahead of the image is not an error.

**If a migration fails.** Postgres DDL is transactional and Flyway runs each migration in its own transaction, so the failed migration rolled back on its own and wrote no history row; migrations that finished before it stay applied. None of the migration files uses a non-transactional statement (`CREATE INDEX CONCURRENTLY` appears only in a comment of `V2_4_1390`). So normally: read the error, fix the SQL, build a new image tag, run again. `repair --yes` is for a checksum you changed on purpose or a history row left failed by a non-transactional statement, not routine. Restore only when data is damaged or the schema must go back to the pre-release state. The nightly and the pre-release dumps are `pg_dump -Fc` files `/backups/ipass-<utc>.dump` (and `keycloak-<utc>.dump`, which migrations never touch) in volume `ipass-backups` (`backup/backup.sh`):
```
docker compose stop platform-app
docker compose exec -T pg-backup ls -1t /backups                      # pick the pre-release ipass-<utc>.dump
docker compose exec -T postgres psql -U ipass_admin -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE ipass WITH (FORCE)" -c "CREATE DATABASE ipass OWNER ipass_migrator"
docker compose exec -T pg-backup cat /backups/ipass-<utc>.dump | docker compose exec -T postgres pg_restore -U ipass_admin -d ipass --exit-on-error
cp -p release-backups/<utc>/.env.bak .env && docker compose up -d     # the previous IMAGE_TAG
```
The role names are the cluster's, so the restore (as the superuser, without `--no-owner`) puts the objects back under `ipass_migrator` with their grants; `restore-test.sh` uses `--no-owner` only because it just counts rows. The sequence was run against a throwaway Postgres in `test/flyway-local-proof.sh` (step 9: 59 migrations still `Success`, owners back to `ipass_migrator`, `migrate` a no-op). Use `POSTGRES_USER` instead of `ipass_admin` if you changed it.

**Tests.** Offline, no container: `bash deploy/hostinger/test/test-flyway.sh` (guard, `.env` parsing without eval, password never on a command line, validate-before-migrate, deploy order and exit codes, with a fake docker). Container proof on the build PC (takes a Docker lock, throwaway Postgres and network, real `postgres-init` role script, deletes what it created): `bash deploy/hostinger/test/flyway-local-proof.sh`. Parity check (the app's own Flyway, same engine and the module `db/migration` folders on the class path, finds 0 pending and a valid history on a CLI-migrated database): `test/FlywayParityCheck.java`, run by `flyway-local-proof.sh`. `PARITY_JAVA` (a JDK 25 `java`) and `PARITY_JARS` (flyway-core 12.4.0, flyway-database-postgresql, the Postgres driver, Jackson 3 databind and core, jackson-annotations; `;`-separated on Windows) are REQUIRED: without them the run fails, and `ALLOW_NO_PARITY=1` ends in `PASSED WITHOUT PARITY`, never in `ALL PASSED`. Step 10 of the proof shows the init SQL (`lock_timeout`) really reaches the migration session, with controls that must fail. The recorded run is the row 528 proof report `_reports/2026-10-27-row528-flyway-pipeline-proof.md` of the project's planning workspace (not in this repository). There is no CI job for these scripts (none exists for this repo): run `bash deploy/hostinger/test/test-flyway.sh` (offline, about two minutes) whenever `flyway/`, `deploy.sh` or a `db/migration` folder changes; `test-scripts.sh` cannot be the gate while it exits early on main (row 489).

## Audit ledger lockdown (option A, row 544)

What changes: the application role (`ipaas_app`, which `ipass_app` is a member of) can no longer INSERT into `audit_event` or UPDATE
`audit_outbox.relayed_at`. A committed `audit_outbox` row reaches the ledger only through `public.audit_relay_move(bigint)` (SECURITY
DEFINER, owner `ipass_migrator`), which stamps `relayed_at` and inserts the ledger row in one statement. Flow and contract approvals
read the approver from the `app.actor_id` the application stamps in the same transaction. Migrations: `V2_4_1490` (`platform-app`,
audit) and `V2_4_1500` (`platform-config`, approvals). All commands below run in `/docker/ipass` and use the real database name `ipass`
(`compose.yaml`, `POSTGRES_DB`); `$PSQL` stands for `docker compose exec -T postgres psql -U ipass_migrator -d ipass -v ON_ERROR_STOP=1 -X`.

**Release order (one release, the app is stopped during the migration).** The previous app cannot work against these migrations (its
relay needs the revoked privileges; it never stamps an actor, so approvals answer `REFUSED_NO_ACTOR`), and `deploy.sh` runs Flyway
while the old app still serves, so stop it first:

1. Baseline: run the reconciliation query below once on the current data (findings go to the owner, they are not fixed here).
2. `docker compose stop platform-app` (business writes pause; nothing is written, so no audit event is lost).
3. `IMAGE_TAG=<sc> ./deploy.sh` - backup, `info`, `validate`, `migrate` (applies V2_4_1490 then V2_4_1500 as `ipass_migrator`), tag switch,
   `up -d` (the new app's own Flyway finds nothing pending; the relay delivers any pending backlog), health.
4. Post-checks (below).

Downtime is migrate duration + app start + health wait (`deploy.sh` waits at most 300 s). Pre-flight on the build PC: the PR is
merged to `main`, both images for the same tag are built, `bash deploy/hostinger/test/test-flyway.sh` is green.

**If `deploy.sh` stops before the app is back up (the app is already stopped).** Each migration runs in its own transaction, so a failed
one wrote nothing; read the error (see "If a migration fails" above). Two states are possible: (a) neither migration applied - the old
app is still compatible: `docker compose up -d platform-app` on the old tag; (b) `V2_4_1490` applied and `V2_4_1500` failed (or the reverse) -
the old app is NOT compatible with either: fix forward with a new image tag and run `deploy.sh` again, or run the rollback blocks of the
applied migration(s) (below) and start the old tag. The pre-release dump (`/backups/ipass-<utc>.dump`, taken in step 1 of `deploy.sh`) is the
last resort and loses writes made since it (there are none while the app is stopped).

**Flag.** `IPAAS_AUDIT_RELAY_MODE` (compose passes it through, default `DEFINER`): `DEFINER` relays through the function; `LEGACY` uses the
old direct statement and logs one WARN at start; any other value stops the app from starting. The flag alone is NOT a rollback.

**Rollback (two steps, in this order).** The rollback SQL lives in the header of each migration as comment lines between
`-- ROLLBACK-BEGIN` and `-- ROLLBACK-END`; this extracts and runs it (from a checkout of the release tag):

```sh
docker compose stop platform-app
sed -n '/^-- ROLLBACK-BEGIN/,/^-- ROLLBACK-END/p' platform-app/src/main/resources/db/migration/V2_4_1490__audit_relay_move_and_lockdown.sql \
  | grep -v '^-- ROLLBACK-' | sed 's/^-- \{0,1\}//' | docker compose exec -T postgres psql -U ipass_migrator -d ipass -v ON_ERROR_STOP=1 -X -1
# then in .env: IPAAS_AUDIT_RELAY_MODE=LEGACY ; and
docker compose up -d
```

If the app **image** is also rolled back, run the same command first for
`platform-config/src/main/resources/db/migration/V2_4_1500__approval_actor_from_session.sql` (restores the previous approval function
bodies) before starting the old tag. Flyway history rows stay; a later roll-forward uses a new migration number, never an edit, and `repair` is
not needed (no checksum changed).

**Post-checks** (`$PSQL`, as the owner). Expected values after the migrations, and after a rollback (the drift check: run the same queries
after the rollback and compare):

```sql
select has_table_privilege('ipaas_app', 'public.audit_event', 'INSERT');                    -- false | true
select has_column_privilege('ipaas_app', 'public.audit_outbox', 'relayed_at', 'UPDATE');    -- false | true
select has_any_column_privilege('ipaas_app', 'public.audit_outbox', 'UPDATE');              -- false | true
select to_regprocedure('public.audit_relay_move(bigint)') is not null;                       -- true  | false
select has_function_privilege('public', 'public.audit_relay_move(bigint)', 'EXECUTE');      -- false | (function gone: error)
select bool_or(prosrc like '%REFUSED_NO_ACTOR%') from pg_proc
  where proname in ('config_approve_version', 'config_approve_contract_version');            -- true  | false
-- the function owner is the migration owner, neither superuser nor BYPASSRLS (cannot be proven in test containers)
select p.proowner::regrole as owner, r.rolsuper, r.rolbypassrls, p.prosecdef, p.proconfig
  from pg_proc p join pg_roles r on r.oid = p.proowner where p.proname = 'audit_relay_move';  -- ipass_migrator, f, f, t, {search_path=pg_catalog, pg_temp}
-- no ACL entry for the application role on any monthly partition (a direct partition INSERT bypasses the parent's grant)
select c.relname, c.relacl from pg_class c join pg_inherits i on i.inhrelid = c.oid
  where i.inhparent = 'public.audit_event'::regclass and c.relacl::text like '%ipaas_app%';  -- no rows
select * from pg_default_acl;                                                                -- nothing for schema public
select r.rolname, m.rolname as member_of from pg_auth_members a join pg_roles r on r.oid = a.member
  join pg_roles m on m.oid = a.roleid where r.rolname in ('ipass_app', 'ipaas_app');         -- only ipass_app -> ipaas_app; neither in pg_write_all_data / pg_read_all_data
select count(*) from public.audit_outbox where relayed_at is null;                           -- falls to 0 after the relay runs
```

Then approve one flow version end to end and confirm the ledger shows the event.

**Reconciliation of `selfApproved` (read-only, one tenant per run).** `audit/reconcile-self-approved.sql` compares the flag on every
`FLOW_APPROVED`, `CONTRACT_VERSION_APPROVED` and `DB_TABLE_APPROVED` event with the business row (approver = author, or decided_by =
requested_by). No rows returned = everything reconciles; any row is a mismatch to investigate (it is never repaired automatically). A
malformed `after_state`, a non-boolean flag or a non-array `target_ids` does not abort the run: that row is reported itself (its
`claimed_self_approved` or `target_id` is empty). Run one tenant, one psql session (stdin keeps the tenant setting and the query together;
needs PostgreSQL 16+ for `pg_input_is_valid`):

```sh
{ echo "select set_config('app.tenant_id', '<tenant uuid>', false);"; cat audit/reconcile-self-approved.sql; } | $PSQL
```

It detects a claim that disagrees with the business row; it cannot detect an event that was never written.

**Concurrent relays.** The relay picks the oldest pending ids with a plain `select` and takes the row lock inside `audit_relay_move` (`skip locked`).
Two relays running at the same moment on one tenant therefore pick the same ids: the second moves 0 until the first commits, then finds them
relayed. Nothing is lost or duplicated; throughput under a large backlog is serial (200 per cycle on the loop, 50 after a commit).

**Known limits.**
- A compromised application session can still write false actor/action text into the outbox at write time, including a row inserted
  already marked `relayed_at` (the application keeps table-level INSERT on `audit_outbox`; such a row never reaches the ledger). That equals
  the application simply not writing the event: the omission class, not detectable by this option. Not fixed in this release.
- The ledger is append-only for the application role. It is not tamper-proof against code execution in the app container, which today also holds
  the migration-owner login (`DB_MIGRATION_*` in the same service, in-app Flyway), nor against the migration owner / DBA, who can still alter
  rows or drop partitions.
- Flow-version `approver_id` / `status` stay writable by the application role through the `interface_version` column grants (`V2_2_4`) - a separate residual.
- `app.actor_id` is stamped by the application: a consistency check, not a security boundary.

## Tests (build PC)

- Offline: `bash deploy/hostinger/test/test-scripts.sh` (dry runs, secret hygiene, compose controls). Run it from any directory; needs `bash` (it re-runs itself under bash and leaves POSIX mode if started as `sh`), `openssl`, `awk`, `sed`, `grep`, `mktemp` and `git` on PATH, and stops with `MISSING PREREQUISITE: <tool>` otherwise. No Docker, `pg_dump` or `psql` is needed.
- Rate limit proof: `NGINX_TEST_TAG=<tag of a local ipass/ui image> bash deploy/hostinger/test/edge-ratelimit.sh` (one stub upstream + the real `edge/edge.conf` in nginx; takes the Docker lock, exits 2 without starting if a live owner holds it).
- Ops / auth edge rules: `NGINX_TEST_TAG=<tag> bash deploy/hostinger/test/edge-ops.sh` (slotted Docker lock `.docker-suite.lock-N`); `bash deploy/hostinger/test/ops-setup-stub.sh` (no Docker). `test-scripts.sh` runs both when `NGINX_TEST_TAG` names a local `ipass/ui` image.
- Whole stack: `IMAGE_TAG=<sc> bash deploy/hostinger/test/smoke.sh` (also asserts the edge allow-list, the web-network membership, the K1 refusal C1, a live DB password rotation) with local images `ipass/<name>:<sc>` - takes the PC-wide docker-suite lock, uses dummy secrets in a temp file, deletes everything afterwards.

## Operator address ops.<domain> (T219) - quick start

`https://ops.<domain>/kafka/` = Kafbat, read-only, no message bodies, behind a Keycloak login (realm `platform`, role `ops-operator`, client `ops-kafbat`, flow `ops-browser` with a one-time code). The Keycloak admin console and the master realm are **not** served on ops (404); admin work is `kcadm.sh` in the container over SSH. Full description, operator-account steps, first-login behaviour, security status: [`documents/deployment.md`](../../documents/deployment.md) section 7.

- **First time / existing install:** run `./keycloak/ops-setup.sh` FIRST, before any other `docker compose` command - `compose.yaml` requires `OPS_KAFBAT_CLIENT_SECRET`, which the script generates in `.env` when missing (fingerprint printed, value never). It needs the named master admin: `KC_ADMIN_USERNAME` / `KC_ADMIN_PASSWORD` in the environment, or `./.env.kc-admin` (mode 600; create once with `umask 077; printf 'KC_ADMIN_USERNAME=%s\nKC_ADMIN_PASSWORD=%s\n' "$name" "$pw" > .env.kc-admin`), else it asks (password read silently). It creates the role, the client and the flow, re-verifies the flow on every run (rebuilds it if it drifted), binds it only after it verified, and always ends with the client ENABLED. Then `docker compose up -d kafbat edge`. DNS: one A record `ops`. Creates no user.
- **After ANY change to `edge/edge.conf`, recreate the edge** (`docker compose up -d --force-recreate --no-deps edge`); `nginx -s reload` is not enough, because the file is a single-file bind mount and replacing it on disk leaves the running container on the old inode.
- **Operators:** the owner creates the named users in realm `platform`, assigns `ops-operator`, adds the required action `CONFIGURE_TOTP` and enrols them in person.
- **Master realm Frontend URL:** on a server where it was ever set (an earlier T219 step did), clear it once (`kcadm.sh update realms/master -s attributes.frontendUrl=` inside the container, named admin) and check that `kcadm` still logs in.
- The edge refuses, on ops, any path containing `%`, `;`, a backslash, `//` or a dot segment (`.` or `..`, also directly before a query string), so a Kafbat topic name that needs URL-encoding is 404 there by design; the request URI is forwarded to Kafbat unchanged.
- **Rollback:** restore `compose.yaml`, `edge/edge.conf` and `.env` from the backup directory taken before the change, `docker compose up -d --remove-orphans` (this also removes the kafbat container when the restored compose has no such service), and force-recreate the edge. The Keycloak role, client and flow may stay (inert without kafbat); the `ops` DNS record may stay.

## Live state (27-Oct, row 489 / 473) - what runs on the VPS now

**Compose files.** The main stack is one project (`ipass`, `/docker/ipass`) built from three files, always passed together:
`F="-f compose.yaml -f demo/compose.demo.yaml -f kafka-public/compose.kafka-public.yaml"` (base stack; WireMock demo destination, `demo/compose.demo.yaml`; Kafka front overlay, `kafka-public/compose.kafka-public.yaml:2-3`). The overlay needs `IPASS_KAFKA_PUBLIC_HOST` in `.env` (the public address Kafka advertises, `compose.kafka-public.yaml:13`; name only here). The demo Postgres door is a separate project, `ipass-outside` (`docker compose --env-file .env -f outside/compose.outside.yaml ...`, `outside/compose.outside.yaml:1-7`); it does not touch the main project.

**Hosts, routes and ports.**

| Where | What answers | Source |
|---|---|---|
| `ipass.<domain>` (443) | UI, `/api`, `/oauth2`, `/login`, `/logout`, plus `/actuator`, `/v3`, `/swagger-ui` passed to the app (the app answers `health` anonymously and refuses the rest) | `edge/edge.conf:142-151` |
| `auth.<domain>` (443) | All of Keycloak, admin console and `master` realm included | `edge/edge.conf:4,29-54` |
| `ops.<domain>` (443) | `/kafka/` Kafbat behind the Keycloak login; `/__admin/` WireMock admin and UI (anonymous, writable); anything else 404 | `edge/edge.conf:58-114` |
| host 5432 | Demo Postgres door: HAProxy `pg-front` to `pg-demo` (snapshot copy of one demo tenant), TLS with a pinned self-signed certificate, one read-only role, throttled per source | `outside/OUTSIDE-ACCESS.md` section 1 |
| host 9092 | Kafka front (HAProxy TCP to the broker's EXTERNAL listener), plaintext, no auth, no ACLs, capped at 60 connections | `kafka-public/haproxy.cfg:1-3` |
| 22, 80 | SSH; HTTP (Traefik) | |

The provider firewall group therefore allows TCP **22, 80, 443, 5432, 9092**. Docker publishes bypass the host firewall, so the host side is the `DOCKER-USER` rules below, not ufw.

**Security status - open by Dipesh's decision 27-Oct (not a security pass):** the Keycloak `master` realm has no one-time code until T220 (needs the owner at the PC); Kafka on 9092 is plaintext with no authentication; the WireMock admin on ops is anonymous and writable (it can be stopped or its stubs changed; Docker restarts it); the demo Postgres door and Kafka are open to any address, limited only by the caps above. The Release 1 posture and the known-gaps table are in [`documents/deployment.md`](../../documents/deployment.md) section 9 (its sections 2, 5 and 9 still describe the state before 27-Oct and need their own update).

**Rollback pointers.**
- Demo Postgres door: [`outside/OUTSIDE-ACCESS.md`](outside/OUTSIDE-ACCESS.md) section 7 (R1 to R4, each step safe to repeat); the throttle rules are section 5.
- Kafka front: the rollback commands at the top of `kafka-public/compose.kafka-public.yaml` (remove `kafka-front`, `kafka-public-fw.sh off`, recreate `kafka`, remove the two networks).
- Edge, compose and `.env` changes (routes, WireMock admin, Keycloak admin): restore from the backup directory, [`documents/deployment.md`](../../documents/deployment.md) section 6.1 (edge rules: section 5), then force-recreate the edge.
- Firewall rules at boot: the removal commands in the next section.

## Firewall rules survive a reboot (install - review first, then run on the VPS)

**Problem.** The two host rule sets live in the kernel only. `outside/pg-throttle.sh` (`DOCKER-USER`, chain `IPASS-PG`, 5432 throttle and `br-ipass-pgpub` no-egress; header lines 1-21) and `kafka-public/kafka-public-fw.sh` (`DOCKER-USER`, `br-ipass-kpub` no new outbound connections; `kafka-public-fw.sh:2-3`) say "not persistent across a reboot or a Docker restart". Read-only inspection of the VPS on 27-Oct found no unit, cron entry or saved ruleset for either (`ufw` is inactive; the only firewall unit is the unrelated `hezo-firewall.service`), so after a reboot both doors are unthrottled and the Kafka bridge may open outbound connections again. **A real reboot test was not run** (it needs the owner's decision); the files below were only checked with `systemd-analyze verify` on a copy under `/tmp` of the VPS (exit 0, no message) and the scripts' `on` / `off` / `check` were exercised against a stand-in `iptables` (3 x `on` leaves 6 rules, no duplicate; 2 x `off` leaves 0; `check` fails after `off`).

**The fix** is one systemd oneshot per script, `kafka-public/ipass-kafka-fw.service` and `outside/ipass-pg-throttle.service`: `After=` and `Wants=docker.service`, `RemainAfterExit=yes`, `ExecStart=... on`, `ExecStartPost=... check` (the unit fails visibly if a rule is missing), `ExecStop=... off`. Additions to the minimum: `PartOf=docker.service` (a `systemctl restart docker` also re-applies the rules) and, for the throttle, `network-online.target` (the script reads the external interface from the default route). Both scripts are idempotent (`iptables -C` before `-I`; the throttle flushes and refills its own chain), so a unit start on top of the rules that are already live changes nothing. `systemctl stop <unit>` removes the rules (that is what `ExecStop` is for); a plain `disable` does not.

**Install** (as root on the VPS, in `/docker/ipass`, after the PR is merged and the two files are copied to `/docker/ipass/kafka-public/` and `/docker/ipass/outside/` with the rest of the folder; this is the owner's step, nothing here has been run):
```
cd /docker/ipass
cp kafka-public/ipass-kafka-fw.service outside/ipass-pg-throttle.service /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/ipass-kafka-fw.service /etc/systemd/system/ipass-pg-throttle.service   # prints nothing = ok
systemctl daemon-reload
systemctl enable --now ipass-kafka-fw.service ipass-pg-throttle.service
systemctl status ipass-kafka-fw.service ipass-pg-throttle.service --no-pager      # active (exited)
sh outside/pg-throttle.sh check && sh kafka-public/kafka-public-fw.sh check       # all OK
iptables -S DOCKER-USER                                                           # each rule once
```
Optional proof, in a quiet window: `reboot`, then run the last two commands again and the outside checks (`outside/verify.sh`, a Kafka client).

**Remove** (restores the state before this change; the live rules stay until the next reboot unless `stop` is run):
```
systemctl disable --now ipass-kafka-fw.service ipass-pg-throttle.service    # --now runs ExecStop = rules off; add the rules back by hand with `on` if they should stay
rm /etc/systemd/system/ipass-kafka-fw.service /etc/systemd/system/ipass-pg-throttle.service
systemctl daemon-reload
```
To keep the rules but drop the units: after the removal run `sh kafka-public/kafka-public-fw.sh on` and `sh outside/pg-throttle.sh on`.
