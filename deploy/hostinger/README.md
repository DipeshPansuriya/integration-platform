# iPass showcase on the Hostinger VPS - runbook

Source: ADR-H-IPASS-01 (hub: `_personal/_project_ipass/integration-platform/_documents/2026-10-24-adr-hostinger-hosting.md`) and the house guide `host-an-app-on-hostinger-vps.md`. This is a showcase/staging copy with fictional data only. `${IPASS_DOMAIN}` is a placeholder until the domain is named; hostnames are `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}`. No secret appears in this file or in git. Every production step below needs Dipesh's approval at the permission prompt.

## What is in this folder

| File | Purpose |
|---|---|
| `compose.yaml` | The stack: app, ui, keycloak, postgres, kafka (+ `kafka-init`), `pg-backup`; `wiremock` only under `--profile demo` |
| `env.example` | Variable NAMES only |
| `bootstrap-env.sh` | Generates every secret on the server into a root-only `.env`; prints fingerprints only |
| `postgres-init/` | `10-keycloak-database.sh`, `20-platform-roles.sh` (the four DB roles + the backup role) |
| `kafka/create-topics.sh` | Creates the three platform topics |
| `backup/` | `backup.sh` (nightly dump loop), `restore-test.sh` |
| `seed/` | `seed-showcase.sh` + `seed.sql` (two tenants, four users) |
| `test/` | `test-scripts.sh` (offline), `smoke.sh` + `compose.smoke.yaml` (build-PC only) |

Images (built elsewhere, see "Build"): `acsnewacr.azurecr.io/ipass/platform-app:<sc>`, `.../ui:<sc>`, `.../keycloak:26.8.0-<sc>`. `<sc>` = short commit of the merged `main`. Never `latest`.

## Build (build PC, Arjun - step 6 of the ADR, outward push needs Dipesh's approval)

```
JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests
(cd ui && npm ci && npx vite build)
docker build -f platform-app/Dockerfile -t acsnewacr.azurecr.io/ipass/platform-app:<sc> .
docker build -f ui/Dockerfile -t acsnewacr.azurecr.io/ipass/ui:<sc> .
docker build -f deploy/hostinger/keycloak/Dockerfile -t acsnewacr.azurecr.io/ipass/keycloak:26.8.0-<sc> .
```
Scan the source tree and the three images (gitleaks, plus a vulnerability scan; Critical/High = no push) before any push.

## Runbook for Maya (on the VPS, over SSH)

0. **Fit rule first** (ADR Decision 2): `MemAvailable >= 4.5 GB`, free disk `>= 25 GB` on the Docker data root, `>= 2` vCPU with 15-minute load below 50 %, Traefik present on the `web` network. Fail any one: stop and report to Mira (new VPS = Dipesh's spend).
1. **DNS**: A records `ipass.${IPASS_DOMAIN}` and `auth.${IPASS_DOMAIN}` to the VPS IPv4 (and AAAA if the VPS has IPv6). Wait until both resolve.
2. **Firewall**: group allows TCP 22, 80, 443 only (IPv4 and IPv6), shows `synced`.
3. **Log retention**: `/etc/systemd/journald.conf.d/ipass.conf` with `MaxRetentionSec=30day` and `SystemMaxUse=2G`; `systemctl restart systemd-journald`. Check no other stack relies on longer retention (ADR Decision 6).
4. **Project folder**: `mkdir -p /docker/ipass && cd /docker/ipass`; copy this `deploy/hostinger/` folder's contents here (`scp -r`), then `chmod +x *.sh */*.sh`. (The postgres init scripts must be executable.)
5. **Secrets**: `IPASS_DOMAIN=<domain> IMAGE_TAG=<sc> ./bootstrap-env.sh` writes `.env` (mode 600) and prints fingerprints. Dipesh keeps a copy of `PLATFORM_CREDENTIAL_KEK_K1` in his password manager (losing it makes sealed credentials unreadable). Add optional lines to `.env` as needed:
   - demo destination: `IPASS_PROFILES=showcase`, `IPASS_DESTINATION_HOSTS=wiremock`, `IPASS_SHOWCASE_LOCAL_HOSTS=wiremock`, `IPASS_SHOWCASE_LOCAL_PORTS=8089`, and start with `--profile demo`.
6. **Registry**: `docker login acsnewacr.azurecr.io` with the pull-only token (Dipesh creates it; `--password-stdin`).
7. **Validate**: `docker compose config -q` (must print nothing).
8. **Start**: `docker compose pull && docker compose up -d` (add `--profile demo` for WireMock). First start: Postgres runs the init scripts (roles), Keycloak imports realm `platform`, `kafka-init` creates the topics, the app runs Flyway and starts.
9. **Wait and verify** (guide section 8): all containers `Up` and healthy, restart count not rising for 90 s; `docker compose logs platform-app` shows a clean start.
10. **Seed**: `./seed/seed-showcase.sh` (creates the four realm users with the generated showcase passwords, binds tenants and roles). Hand the showcase passwords to Dipesh through the OS store, never through chat.
11. **Keycloak admin**: replace the bootstrap admin by a named one with `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...` over SSH, then remove `KC_BOOTSTRAP_ADMIN_PASSWORD` from `.env` and re-create keycloak. `/admin` and the `master` realm are not routed publicly.
12. **Outside checks** (ADR conformance C2/C3): anonymous `GET https://ipass.${IPASS_DOMAIN}/api/v1/...` is 401; `https://auth.${IPASS_DOMAIN}/admin` is 404; `/actuator/prometheus` and `/v3` are 404 from outside; only ports 22/80/443 answer on IPv4 and IPv6.
13. **Facts to verify on the host** (ADR V5/V6/V8): `docker stats --no-stream` after one demo run (alarm above 80 % of a limit); the app can fetch `https://auth.${IPASS_DOMAIN}/realms/platform/.well-known/openid-configuration` from inside its container - if not, add `extra_hosts: ["auth.${IPASS_DOMAIN}:host-gateway"]` to `platform-app`; IPv6 is covered by the firewall.
14. **Backup**: `docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh` (also before every change), then **restore test** `docker compose exec pg-backup sh /scripts/restore-test.sh` (expect `OK tenant`, `OK audit_event`). Repeat monthly. Nightly dumps run at 02:00 UTC into volume `ipass-backups`, 7 daily + 4 weekly.

## Update and roll back

1. `cp -p .env .env.bak-$(date -u +%Y%m%dT%H%M%SZ)` and the same for `compose.yaml`; run a manual dump (step 14).
2. Change the `IMAGE_TAG` line only; `docker compose pull && docker compose up -d` for this project only.
3. Verify as in steps 9 and 12.
4. **Roll back**: restore the backup files and the previous tag, `docker compose up -d` once. A release whose Flyway migration already ran cannot roll back the schema: restore the pre-change dump instead. If the same step fails twice, stop and find out why before a third try.

## Rotation

Database passwords: change in `.env`, then re-run `docker compose exec -T postgres sh /docker-entrypoint-initdb.d/20-platform-roles.sh` (it re-sets the role passwords), then `docker compose up -d`. Order: database first, then app and Keycloak. The registry pull token expires every 90 days: create the second password, `docker login` with it, verify a pull, delete the first.

## Tests (build PC)

- Offline: `bash deploy/hostinger/test/test-scripts.sh` (dry runs, secret hygiene, compose controls).
- Whole stack: `IMAGE_TAG=<sc> bash deploy/hostinger/test/smoke.sh` with local images `ipass/<name>:<sc>` - takes the PC-wide docker-suite lock, uses dummy secrets in a temp file, deletes everything afterwards.
