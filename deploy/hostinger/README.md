# iPass showcase on the Hostinger VPS - runbook

Source: ADR-H-IPASS-01 (hosting decision; kept in the planning workspace, not in this repository). This is a showcase/staging copy with fictional data only. `${IPASS_DOMAIN}` is a placeholder until the domain is named; hostnames are `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}`. No secret appears in this file or in git. Every production step below needs the project owner's approval at the permission prompt.

**The whole picture** (links, what is not public and how to reach it, services and image pins, redeploy and rollback by backup directory, the ops address and operator accounts, security status and known gaps) is in [`documents/deployment.md`](../../documents/deployment.md). This README is the operator quick-start: file list, first-install runbook, update, rotation, tests.

## What is in this folder

| File | Purpose |
|---|---|
| `compose.yaml` | The production stack: `edge` (the only service on the shared `web` network), app, ui, keycloak, postgres, kafka (+ `kafka-init`), `kafbat` (read-only operator view, T219), `pg-backup`, one-shot `pg-restore-test`. No test tooling |
| `edge/edge.conf` | The edge proxy path allow-list (actuator, `/v3`, Keycloak `/admin` and the master realm answer 404). Login and token paths are rate limited: about 10 requests/min per client, burst 5, 429 beyond that (the client is the last `X-Forwarded-For` entry, the one Traefik appends - a spoofed first entry is ignored). |
| `test/edge-ratelimit.sh` | Behaviour proof of that rate limit in nginx (build PC, Docker lock) |
| `test/edge-ops.sh`, `test/ops-setup-stub.sh` | Behaviour proof of the ops and auth edge rules in nginx, and of `ops-setup-kcadm.sh` against a stub kcadm |
| `demo/compose.demo.yaml` | Opt-in override: WireMock demo destination + the showcase profile. Never part of `compose.yaml` |
| `keycloak/` | `compose.bootstrap-admin.yaml` (first start only), `retire-bootstrap-admin.sh`, `Dockerfile`; `ops-setup.sh` + `ops-setup-kcadm.sh` (role `ops-operator`, client `ops-kafbat`, flow `ops-browser`; safe to re-run) |
| `kafbat/kafbat-roles.yml` | The Kafbat RBAC role: view-only, no message permission |
| `rotate-secret.sh` | Rotates one secret at a time (database roles, realm client secrets) |
| `env.example` | Variable NAMES only |
| `bootstrap-env.sh` | Generates every secret on the server into a root-only `.env` (and the Keycloak bootstrap admin into `.env.bootstrap-admin`); prints fingerprints only; never overwrites |
| `postgres-init/` | `10-keycloak-database.sh`, `20-platform-roles.sh` (the four DB roles + `ipass_backup` + `ipass_restore`) |
| `kafka/create-topics.sh` | Creates the three platform topics |
| `backup/` | `backup.sh` (nightly dump loop), `restore-test.sh` |
| `seed/` | `seed-showcase.sh` + `seed.sql` (two tenants, four users); `NESTED-APPOINTMENT.md` = hand-off for the nested-appointment showcase flow (not part of the seed run) |
| `test/` | `test-scripts.sh` (offline), `smoke.sh` + `compose.smoke.yaml` (build-PC only) |

Images (built elsewhere, see "Build"): `<registry>/ipass/platform-app:<sc>`, `.../ui:<sc>`, `.../keycloak:26.8.0-<sc>`. `<sc>` = short commit of the merged `main`. `<registry>` is your container registry host. `compose.yaml` still carries a built-in default registry prefix; `IPASS_IMAGE_PREFIX` (the prefix) or `IPASS_APP_IMAGE` / `IPASS_UI_IMAGE` / `IPASS_KEYCLOAK_IMAGE` (whole names) override it. Never `latest`.

The compose `image:` names are overridable per service: `IPASS_APP_IMAGE`, `IPASS_UI_IMAGE`, `IPASS_KEYCLOAK_IMAGE` (full `repo:tag`). Each defaults to the prefix form (the built-in default prefix in `compose.yaml`, overridden by `IPASS_IMAGE_PREFIX`), so the existing `IPASS_IMAGE_PREFIX=<registry>/ipass` setup is unchanged. Docker Hub has one namespace level, so the same tag as in the registry cannot be built from one prefix - to serve the published Hub images set, e.g. `IPASS_APP_IMAGE=dipeshpansuriya/ipass-platform-app:3105c3d3`, `IPASS_UI_IMAGE=dipeshpansuriya/ipass-ui:3105c3d3`, `IPASS_KEYCLOAK_IMAGE=dipeshpansuriya/ipass-keycloak:26.8.0-3105c3d3`. Never `latest`.

## Build (build PC; step 6 of the ADR; an outward push needs the project owner's approval)

```
JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests
(cd ui && npm ci && npx vite build)
docker build -f platform-app/Dockerfile -t <registry>/ipass/platform-app:<sc> .
docker build -f ui/Dockerfile -t <registry>/ipass/ui:<sc> .
docker build -f deploy/hostinger/keycloak/Dockerfile -t <registry>/ipass/keycloak:26.8.0-<sc> .
```
Scan the source tree and the three images (gitleaks, plus a vulnerability scan; Critical/High = no push) before any push.

## Runbook (on the VPS, over SSH)

0. **Fit rule first** (ADR Decision 2): `MemAvailable >= 4.5 GB`, free disk `>= 25 GB` on the Docker data root, `>= 2` vCPU with 15-minute load below 50 %, Traefik present on the `web` network. Fail any one: stop and report to the project owner (new VPS = the project owner's spend).
1. **DNS**: A records `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}` to the VPS IPv4 (and AAAA if the VPS has IPv6). Wait until both resolve.
2. **Firewall**: group allows TCP 22, 80, 443 only (IPv4 and IPv6), shows `synced`.
3. **Log retention**: `/etc/systemd/journald.conf.d/ipass.conf` with `MaxRetentionSec=30day` and `SystemMaxUse=2G`; `systemctl restart systemd-journald`. Check no other stack relies on longer retention (ADR Decision 6).
4. **Project folder**: `mkdir -p /docker/ipass && cd /docker/ipass`; copy this `deploy/hostinger/` folder's contents here (`scp -r`), then `chmod +x *.sh */*.sh`. (The postgres init scripts must be executable.)
5. **Secrets**: `IPASS_DOMAIN=<domain> IMAGE_TAG=<sc> ./bootstrap-env.sh` writes `.env` (mode 600) and prints fingerprints. The project owner keeps a copy of `PLATFORM_CREDENTIAL_KEK_K1` in his password manager (losing it makes sealed credentials unreadable). The temporary Keycloak admin is written to its own file `.env.bootstrap-admin` (mode 600), not to `.env`.
   - Demo destination (optional): add `-f demo/compose.demo.yaml` to every compose command below; it brings WireMock and sets `SPRING_PROFILES_ACTIVE=showcase`, the one allow-listed host and the showcase-only exception. Without it none of that exists.
6. **Registry**: `docker login <registry>` with the pull-only token (the project owner creates it; `--password-stdin`).
7. **Validate**: `./check-issuer-override.sh` (fails if `OIDC_ISSUER_URI` is set in the env file without `KEYCLOAK_ADMIN_BASE_URI`) and `docker compose -f compose.yaml -f keycloak/compose.bootstrap-admin.yaml config -q` (must print nothing).
8. **Start** (first time, with the bootstrap admin): `F="-f compose.yaml -f keycloak/compose.bootstrap-admin.yaml"` (append `-f demo/compose.demo.yaml` for the demo), then `docker compose $F pull && docker compose $F up -d`. After step 11 the `keycloak/compose.bootstrap-admin.yaml` file is no longer used. First start: Postgres runs the init scripts (roles), Keycloak imports realm `platform`, `kafka-init` creates the topics, the app runs Flyway and starts.
9. **Wait and verify** (guide section 8): all containers `Up` and healthy, restart count not rising for 90 s; `docker compose logs platform-app` shows a clean start.
10. **Seed**: `./seed/seed-showcase.sh` (creates the four realm users with the generated showcase passwords, binds tenants and roles). Hand the showcase passwords to the project owner through the OS store, never through chat.
11. **Keycloak admin**: create a named master-realm admin with `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...` over SSH, then `KC_ADMIN_USER=<named admin> ./keycloak/retire-bootstrap-admin.sh` (kcadm asks for the password; it deletes the `bootstrap-admin` user and `.env.bootstrap-admin`, and re-creates keycloak from `compose.yaml` alone, so the credential is no longer in the container environment). Later seeding or client-secret rotation uses `KC_ADMIN_USER` the same way. `/admin` and the `master` realm are not routed publicly (Traefik and `edge` both refuse them).
12. **Outside checks** (ADR conformance C2/C3): anonymous `GET https://ipass.${IPASS_DOMAIN}/api/v1/...` is 401; `https://auth.${IPASS_DOMAIN}/admin` is 404; `/actuator/prometheus` and `/v3` are 404 from outside (edge answers 404 itself); only ports 22/80/443 answer on IPv4 and IPv6.
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
- Realm client secrets: `KC_ADMIN_USER=<named admin> ./rotate-secret.sh CONSOLE_OIDC_CLIENT_SECRET` (or `PLATFORM_ADMIN_CLIENT_SECRET`, `OPERATOR_AGENT_CLIENT_SECRET`); kcadm sets the secret in the realm and prompts for the admin password.
- Showcase user passwords: edit the line in `.env`, re-run `./seed/seed-showcase.sh` (with `KC_ADMIN_USER` once the bootstrap admin is retired).
- `PLATFORM_CREDENTIAL_KEK_K1` is **not** rotated by the kit: a new key needs the old one kept until every sealed target credential is re-sealed (application-side step, not built). Keep the copy in the password manager.
- Schedule: rotate the database and client secrets every 90 days (the same cadence as the registry token); `.rotation-log` is the record. The registry pull token: create the second password, `docker login` with it, verify a pull, delete the first.

## Networks and the Docker socket

**Keycloak admin address.** The public auth host hides `/admin` and the `master` realm, so the app cannot create or disable machine clients through the public issuer URL (it would get 404, logged as `credential.source_refused ... reason=identity_provider_unavailable`, HTTP 503). `compose.yaml` therefore gives `platform-app` `KEYCLOAK_ADMIN_BASE_URI` (default `http://keycloak:8080`, the keycloak service on `ipass-front`/`ipass-data`; leave it unset in `.env`): the app sends only the admin REST calls (client create, lookup, disable) there, while tokens and the issuer stay `OIDC_ISSUER_URI` (the public `https://auth.<domain>/realms/platform`). Unset or empty in the app (any other deployment) = the admin URL is derived from the issuer, as before. The value must be a plain `http(s)://host[:port][/path]` address (no user info, query or fragment; trailing slashes are dropped) or the app refuses to start. **An override must be an internal address the edge does not publish** (a private Docker service name, loopback or RFC1918 address): the admin bearer token and the new client secret are sent over it, so a public or routed host would expose them. The app **refuses to start** (fail closed; the message names the setting, never the value) unless the value is `https`, or plain `http` to an internal host: a single-label service name (no dots, such as `keycloak`), a loopback address or an RFC1918 / ULA private address. Plain `http` to a public DNS name or public IP, user info, a query, a fragment, a port outside 1-65535 and an underscore in the host name are refused. **`KEYCLOAK_ADMIN_ALLOW_EXTERNAL`** (default off; leave it unset in `.env`) is the explicit opt-in for an `https` admin address on a host other than the issuer host, as an external identity provider needs; it never allows plain `http` to a public host. It is off by default because that setting sends the admin bearer token and the new client secret to a host outside the issuer, so it must be a deliberate choice. A single-label service name is resolved by the container DNS and its search domains, not by this check, so it must be a trusted internal service name (letters, digits and hyphens, starting with a letter; all-digit, hex and octal hosts such as `134744072` or `0x7f000001` are refused). The admin base is validated only when `PLATFORM_ADMIN_CLIENT_SECRET` is set (the admin beans are conditional on it); a deployment without that secret never reads the value. Not verified without a live run: that Keycloak accepts an admin call on the internal HTTP address with a token whose `iss` is the public URL (`KC_HOSTNAME` is the full public URL, so the issuer it expects is public; the realm's `sslRequired: external` allows plain HTTP from the private Docker network) - after the deploy, one real "issue source credential" check in the console settles it; if Keycloak refuses, the app fails closed (HTTP 503, `identity_provider_unavailable`), never open. **Overriding `OIDC_ISSUER_URI` to an external identity provider?** Then also set `KEYCLOAK_ADMIN_BASE_URI` to that provider's internal admin address: the default above points at the bundled Keycloak only, so admin calls would otherwise go to it with the wrong realm. `./check-issuer-override.sh` (run before `docker compose up`) fails in that case and prints variable names only. It reads the env file only, not the shell environment, so a variable exported in the shell is not seen; it accepts `export NAME=...`, leading spaces, CRLF endings and surrounding quotes, and treats an empty quoted value as unset.

Only `edge` joins the host's shared `web` network, so no other stack on that network can reach the app, the actuator or Keycloak directly. `platform-app` alone has outbound reach (`ipass-egress`) for allow-listed destinations and the public issuer URL; everything else sits on internal networks. No iPass service mounts the Docker socket (checked by `test-scripts.sh` and `smoke.sh`).

The host's Traefik does read the socket (ADR Decision 5 point 8). A read-only mount does not limit what a compromised Traefik can see or do through the socket API. Recommended (outside this stack, the operator and the project owner, not done by the kit): put a socket proxy in front of it that allows only `GET /containers` and `/events` (for example `tecnativa/docker-socket-proxy` with `CONTAINERS=1`, everything else `0`, on an internal network shared only with Traefik), point Traefik's `docker` provider at `tcp://socket-proxy:2375`, and remove the socket mount from Traefik. Pin the image by digest when you add it.

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
