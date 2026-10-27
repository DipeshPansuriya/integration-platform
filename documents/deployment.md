# IPASS - deployment

How the integration platform is built, run and operated: the local development stack, and the hosted instance (showcase/staging copy on a personal VPS, ADR-H-IPASS-01). State as verified from outside on 26-Oct (tasks T218 and T219). This document is the whole picture; the step-by-step first-install runbook and the scripts live in [`deploy/hostinger/README.md`](../deploy/hostinger/README.md) and are not repeated here.

**Conventions.** `<domain>` is the value of `IPASS_DOMAIN` in the server `.env`; `<vps>` is the server's SSH host name or address; the stack directory on the server is `/docker/ipass`. No server address, key or secret value is written in this repository: secrets exist only in the server `.env` (root-only), in `.env.kc-admin`, and in the owner's password manager, and are named here by variable name only.

## Contents

1. [Two ways to run it](#1-two-ways-to-run-it)
2. [Links, what is not public, services, images](#2-links-what-is-not-public-services-images)
3. [Local stack](#3-local-stack)
4. [Hosted kit: files, seed data, tests](#4-hosted-kit-files-seed-data-tests)
5. [Edge rules](#5-edge-rules)
6. [Redeploy a new build, rollback](#6-redeploy-a-new-build-as-done-for-t218t219)
7. [Operator address on the ops host](#7-operator-address-on-the-ops-host)
8. [Master realm state and the console](#8-master-realm-state-and-the-console)
9. [Security status and known gaps](#9-security-status-and-known-gaps)
10. [Open items](#10-open-items)

## 1. Two ways to run it

| | Local stack | Hosted kit |
|---|---|---|
| Where | A developer machine, `compose/compose.yaml` | The VPS, `deploy/hostinger/compose.yaml` (+ optional `demo/compose.demo.yaml`) |
| What | PostgreSQL, Keycloak, Kafka and the engineers' Kafbat; the application runs from the IDE or Maven | The whole product: edge, ui, platform-app, Keycloak, Postgres, Kafka, Kafbat, backups; images from a registry |
| Data | Test data | Fictional showcase data only (two tenants, four users) |
| Exposure | None (local) | Three public links behind the host's Traefik; everything else internal |

## 2. Links, what is not public, services, images

### Links

| Link | Answer | What it is |
|---|---|---|
| `https://ipass.<domain>` | 200 | The app UI. Sign-in redirects to Keycloak |
| `https://auth.<domain>/realms/platform/account` | 200 | Keycloak login and account page, realm `platform` |
| `https://ops.<domain>/kafka/` | 302 to the login | Kafbat: read-only Kafka view (topic names, partitions, offsets, consumer groups, counts; no message bodies). Operators only, password + one-time code |

### Deliberately not public - reach it over SSH

Closed on every public host: Keycloak `/admin` and the `master` realm, `/actuator`, `/metrics`, `/health`, `/v3`, swagger, Postgres 5432, Kafka 9092, the WireMock admin. Nothing publishes a port; only 22/80/443 answer on the VPS. From `ssh <user>@<vps>` and `cd /docker/ipass`:

| Need | How (inside the stack, nothing opened to the internet) |
|---|---|
| Keycloak admin work | `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...` (log in against `http://localhost:8080`, realm `master`, named admin) - see "Operator address" below for why the browser console is not offered |
| Postgres | `docker compose exec postgres psql -U "$POSTGRES_USER" -d ipass` (the `.env` is root-only) |
| Kafka | `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| App health | `docker compose exec platform-app bash -c '...'` against `127.0.0.1:8080/actuator/health/readiness` (the same probe the healthcheck uses) |
| WireMock admin (demo only) | `docker compose -f compose.yaml -f demo/compose.demo.yaml exec wiremock curl -s localhost:8089/__admin/mappings` |

### Services

| Service | Image | Network | Note |
|---|---|---|---|
| `edge` | `ui` image (nginx) with `edge/edge.conf` | `web` (shared Traefik), `ipass-front` | The only service on the shared network; path allow-list and rate limits; TLS and routing by the host's Traefik |
| `ui` | `ui` image | `ipass-front` | Static front end |
| `platform-app` | `platform-app` image | `ipass-front`, `ipass-data`, `ipass-work`, `ipass-egress` | The only service with outbound reach |
| `keycloak` | `keycloak` image (realm baked in) | `ipass-front`, `ipass-data` | Hostname `auth.<domain>`, strict |
| `kafbat` | `kafbat/kafka-ui` (version + digest pinned in `compose.yaml`) | `ipass-front`, `ipass-work` | Read-only, Keycloak login, no egress |
| `postgres`, `pg-backup` | `postgres:17.11-alpine` | `ipass-data` | `pg-backup` dumps nightly |
| `kafka`, `kafka-init` | `apache/kafka` (internal PLAINTEXT) | `ipass-work` | `kafka-init` creates the three platform topics and exits |
| `wiremock` | demo override only | `ipass-work` | Demo destination stub, internal only |
| `pg-restore-test` | `postgres:17.11-alpine` | `ipass-data` | One-shot (`--profile restore-test`), not a resident service |

`ipass-front`, `ipass-data` and `ipass-work` are internal networks (no internet). Only `platform-app` is on `ipass-egress`.

### Images and how the pins work

The three product images are built from `main` and pushed to Docker Hub (public). The server `.env` pins each one in full with `IPASS_APP_IMAGE`, `IPASS_UI_IMAGE` and `IPASS_KEYCLOAK_IMAGE` (`repo:tag`); they win over the `IPASS_IMAGE_PREFIX`/`IMAGE_TAG` form, so the app, the ui/edge and Keycloak can sit on different tags. Never `latest`. Third-party images are pinned in `compose.yaml` (Kafbat by version and digest).

**Which tag is running is whatever `IPASS_APP_IMAGE` (and the other two) say in the server `.env` - this document does not record a tag, because a redeploy changes it.** Read it without printing secrets: `docker compose -f compose.yaml -f demo/compose.demo.yaml ps --format '{{.Service}} {{.Image}}'` (the image each service actually runs), and `docker inspect <container> --format '{{.Image}}'` for the image id. Do not print the whole `.env`: it holds secrets.

## 3. Local stack

`compose/compose.yaml` is the development harness: PostgreSQL, Keycloak (realm `platform` imported from `compose/keycloak/platform-realm.json`), Kafka and the engineers' Kafbat; the `e2e` profile adds Artemis and WireMock. Every image is pinned to the tag the tests use; there is no literal credential - every value comes from a variable listed (names only) in `compose/compose.env.example`, set from the OS secret store before `docker compose up`.

```
docker compose -f compose/compose.yaml up -d                    # postgres, keycloak, kafka, kafbat
docker compose -f compose/compose.yaml --profile e2e up -d      # adds artemis + wiremock (ARTEMIS_* must be set too)
```
One Docker suite at a time on the build PC (the PC-wide Docker lock). Locally Kafbat joins the operations network only and has its own login (`KAFBAT_UI_*` variables); it is the engineers' tool, not the hosted ops view (section 7).

### 3.1 Local setup - allow your destination host

**Symptom.** Saving a system with an external `https` host answers "The save was refused: settings.baseUrl is refused: the host is not on the destination allow-list". That is by design: the outbound allow-list is empty by default and an empty list refuses every destination (fail closed, an SSRF guard). Nothing is reachable until the operator - you, locally - lists the host.

**Fix.** Set `platform.config.destinations.allowed-hosts` (env form `PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS`), a comma-separated list of exact host names or `*.domain` (sub-domains only, not the bare domain; case-insensitive; keep it to hosts you control, not a public suffix such as `*.com` - this is operator guidance, `AllowList` does not enforce it). The list is deployment configuration, read when the application starts: no console screen or API edits it (that waits for the operator login, ADR-009), so every change needs a restart. The database table `operator_allowed_host` is dormant (left from the removed PR #34 screen): nothing reads or writes it, so rows in it have no effect:

```
PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS=uat.example.com,*.partner.example
ROUTING_CORE_ALLOWED_SCHEMES=https
```

- **Local run:** put both variables in the IDE run configuration (or the shell that starts the application with Maven); the compose file above starts no application. Restart the application after a change.
- **Hosted stack:** set `IPASS_DESTINATION_HOSTS=uat.example.com` in the server `.env` (mapped in `deploy/hostinger/compose.yaml`), then `docker compose up -d platform-app` to recreate it. The demo override defaults it to the WireMock host.
- **Schemes:** the destination guard accepts only `http` and `https` URLs and has no scheme setting of its own. `ROUTING_CORE_ALLOWED_SCHEMES` (default: empty) is the routing stage's list; empty approves no scheme, so every delivery route build is refused. Use `https` for external destinations: the guard allows plain `http` to a listed public host, so add `http` only for a local stub. The hosted compose sets `http,https`; set it yourself locally.
- **Still refused when listed:** a name that resolves to a loopback, private, link-local, carrier-grade-NAT, multicast or broadcast address. The platform's own hosts are refused too, but only when `platform.config.destinations.platform-hosts` is set: it is empty by default and no shipped configuration sets it. Outside the showcase exception below, listing `localhost` does not help.
- **Testing against a local stub (WireMock etc.):** run with the `showcase` profile (`local`, `dev` and `test` may accompany it; any other active profile refuses to start) and set `PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_HOSTS` (exact allow-listed names, no wildcard, no IP) together with `PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_PORTS` (the stub's port; never 5432, 9092 or the app's own `server.port`/management port). The host must also be in `allowed-hosts`. Showcase only; the application logs a WARN at start.

## 4. Hosted kit: files, seed data, tests

The folder `deploy/hostinger/` holds the compose file, `edge/edge.conf`, the Keycloak image recipe and scripts, the Kafbat role file, the Postgres init scripts, the backup scripts, `bootstrap-env.sh` (generates every secret on the server, prints fingerprints only), `rotate-secret.sh`, and `env.example` (names only). Its [README](../deploy/hostinger/README.md) lists every file and carries the first-install runbook (steps 0-14), update/rollback, rotation and the network notes.

**Seed / showcase data.** `deploy/hostinger/seed/seed-showcase.sh` creates four fictional realm users (passwords generated into the server `.env` as `SHOWCASE_PASSWORD_*`, never printed) and binds two tenants with `seed.sql`; run it once after the stack is healthy. `deploy/hostinger/seed/NESTED-APPOINTMENT.md` is the hand-off for the nested-appointment showcase flow (its driver scripts are under `e2e-ui/scripts/`; it is not part of the seed run). The WireMock stub used as the demo destination exists only with the demo override. (There is no separate `seed/README.md`; the two files above are the seed documentation.)

**Tests** (build PC, from the repository root):

| Command | What it proves |
|---|---|
| `bash deploy/hostinger/test/test-scripts.sh` | Offline: dry runs, secret hygiene, compose controls (no published port, no Docker socket, pinned images, networks), edge and ops rules as text, README grep-lines. With `NGINX_TEST_TAG=<local ipass/ui tag>` it also runs the two nginx behaviour scripts below |
| `NGINX_TEST_TAG=<tag> bash deploy/hostinger/test/edge-ratelimit.sh` | The login rate limit in a real nginx with a stub upstream (takes the Docker lock) |
| `NGINX_TEST_TAG=<tag> bash deploy/hostinger/test/edge-ops.sh` | The ops and auth rules in nginx: message paths in many spellings, percent / dot / `;` paths, methods, h2c, rate limits including the trailing-slash form, forwarded URI unchanged (slotted Docker lock) |
| `bash deploy/hostinger/test/ops-setup-stub.sh` | `keycloak/ops-setup-kcadm.sh` against a stub kcadm: order, negated role gate, bind-after-verify, rebuild on drift, fail-closed |
| `IMAGE_TAG=<sc> bash deploy/hostinger/test/smoke.sh` | Whole stack on the build PC with local images and dummy secrets (heavy; takes the Docker lock) |

## 5. Edge rules

`deploy/hostinger/edge/edge.conf` (nginx) is the path allow-list in front of the app, Keycloak and Kafbat; Traefik only does TLS and host routing.

- Login, token and `login-actions/authenticate` paths are rate-limited (about 10 requests a minute per client, burst 5, 429 beyond); the trailing-slash forms are limited too. The client key is the last `X-Forwarded-For` entry, the one Traefik appends.
- `ipass.<domain>`: `/api`, `/oauth2`, `/login`, `/logout` go to the app, everything else to the ui; `/actuator`, `/v3`, swagger answer 404.
- `auth.<domain>`: Keycloak, except `/admin` and the `master` realm (404 here and unrouted in Traefik too).
- `ops.<domain>`: only Kafbat under `/kafka/` - GET only on the API; 404 for messages, serdes, smartfilters, produce, actuator; 404 for any path containing `%`, `;`, a backslash, `//` or a dot segment (the query string is excluded); the request URI is forwarded unchanged; no `Upgrade`/`Connection` header is forwarded to Keycloak or Kafbat.
- Known Low (N4): the literal blocks are case-sensitive; no anonymous impact; fix with the next edge change.
- **After ANY `edge.conf` change, force-recreate the edge** (section 6). A reload is not enough.

## 6. Redeploy a new build (as done for T218/T219)

1. **Build** from a detached, throwaway worktree of `main`, never from your working tree: JDK 25, first the test gate (run `./mvnw -o test` on the SHA, or cite a green SHA whose `git diff --name-only` to the deploy SHA touches only deploy scripts or docs; a deploy never ships untested app code), then `./mvnw -o -pl platform-app -am package -DskipTests` (the tests already ran at the gate), then `docker build` of the changed image(s) with the short commit as tag. Take the PC-wide Docker lock for the build and release it afterwards. Push to the registry only with the project owner's approval.
2. **Back up on the server** before touching anything: a new directory `/docker/ipass-backups/<UTC stamp>-<task>/` (mode 700) holding `compose.yaml`, `edge/edge.conf` and `.env` (mode 600, copied with `cp -p`), and a database dump (`docker compose exec pg-backup env RUN_ONCE=1 sh /scripts/backup.sh`, check it reports no `backup FAILED`).
3. **Change the pin**: edit only the `IPASS_*_IMAGE` line(s) in `.env`; copy changed repo files into `/docker/ipass`; `docker compose -f compose.yaml -f demo/compose.demo.yaml config -q` must print nothing (drop the demo override on an install without WireMock).
4. **Recreate only what changed**: `docker compose -f compose.yaml -f demo/compose.demo.yaml up -d <service>` (add `--no-deps`; `--force-recreate` when the image tag did not change). Wait until the services are `healthy`.
   - **`edge/edge.conf` changed: force-recreate the edge** (`up -d --force-recreate --no-deps edge`). `nginx -s reload` is not enough: the config is a single-file bind mount, so replacing the file on disk leaves the running container on the old file. Check with `docker compose exec edge nginx -T | grep <a line you changed>`.
   - `kafbat/kafbat-roles.yml` is a single-file mount too: recreate `kafbat` after changing it.
5. **Verify from outside** (not from the server): the links table above; `https://auth.<domain>/admin/` and `/realms/master/...` are 404; on ops `/admin/`, `/realms/master/...` and any `%`-path are 404; `/kafka/` is 302 to the login.
6. **Post-deploy credential check (app image or Keycloak change):** sign in as a showcase admin, issue one source credential, check the response is 201, then revoke it (200). A 503 / `credential.source_refused` means the app cannot reach the Keycloak admin API. This is the check that proves `KEYCLOAK_ADMIN_BASE_URI` works. On the hosted instance that variable is **unset in `.env`**, so it takes the internal default `http://keycloak:8080` (the public edge hides `/admin`); `./check-issuer-override.sh` guards the one case where it must be set.
7. **Roll back** to the backup directory (below). If the same step fails twice, stop and find out why before a third try.

### 6.1 Rollback by backup directory

Each change has its own `/docker/ipass-backups/<stamp>-<task>/` (e.g. `20261025T105918Z` before T218, `...-t219`, `...-t219-p1`, `...-t219-p2`, `...-t219-n1`, `...-t219-n1b` for the T219 steps). To go back:

```
cd /docker/ipass
B=/docker/ipass-backups/<chosen dir>
cp -p $B/compose.yaml . && cp -p $B/edge/edge.conf edge/          # and .env only if the change touched it
docker compose -f compose.yaml -f demo/compose.demo.yaml up -d --force-recreate --no-deps edge
docker compose -f compose.yaml -f demo/compose.demo.yaml up -d --remove-orphans   # when compose.yaml itself went back
```
App rollback: set `IPASS_APP_IMAGE` back to the previous tag in `.env` and recreate `platform-app` (an older app without the separate admin address returns 503 when issuing credentials; that is the previous known state). A release whose Flyway migration already ran cannot roll the schema back: restore the pre-change dump instead.

## 7. Operator address on the ops host

`https://ops.<domain>/kafka/` is the read-only Kafka view for named operators. Everything on the ops host is behind a Keycloak login; nothing is anonymous. The Keycloak admin console and the master realm are **not** served there.

### 7.1 Components and set-up

| Piece | Name / where |
|---|---|
| Kafbat | `kafbat` service, `kafbat/kafbat-roles.yml` (RBAC role `ops-operator-readonly`, view-only, no message permission), cluster flag `readOnly`, Secure session cookie |
| Realm role | `ops-operator` in realm `platform` |
| Client | `ops-kafbat` (confidential, authorization-code only, full scope off, only that role mapped, default scope `basic` only) |
| Login flow | `ops-browser` (password, deny users without the role, one-time code), bound to that client only; platform logins are unchanged |
| Secret | `OPS_KAFBAT_CLIENT_SECRET` in the server `.env` (generated by the setup script; name only) |
| Setup script | `keycloak/ops-setup.sh` (host side) + `keycloak/ops-setup-kcadm.sh` (runs in the Keycloak container) |

Why no message bodies: the cluster `readOnly` flag stops writes only. Reads are held back by two layers - the RBAC role and the edge (404 for the messages / smartfilters / serdes API paths, GET only). Both sit in front of an unauthenticated PLAINTEXT broker on the internal network; a broker-side ACL is the follow-up (section 9).

Set-up on a server (once; safe to re-run): add the DNS A record `ops`; copy the repo files; run `./keycloak/ops-setup.sh` **before any other `docker compose` command** (compose now requires `OPS_KAFBAT_CLIENT_SECRET`, which the script generates when missing). It needs the named master admin (`KC_ADMIN_USERNAME`/`KC_ADMIN_PASSWORD` in the environment, or `.env.kc-admin` mode 600, or it asks; the password is read silently). The script creates the role, client and flow, verifies the flow on every run (rebuilds it if it drifted), binds it only after it verified, and ends with the client enabled. Then `docker compose up -d kafbat edge` (force-recreate the edge if `edge.conf` changed). The script creates no user.

### 7.2 Operator accounts and first login

Two named operator accounts at most (the owner and one deputy). Users are created by the owner in realm `platform`, never by a script, and no password travels through chat, a file or a command line.

1. Log in to `kcadm` in the container with the named master admin (`docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user <named admin>`; it asks for the password).
2. Create the user and give the role: `kcadm.sh create users -r platform -s username=<name> -s enabled=true -s 'requiredActions=["CONFIGURE_TOTP"]'`, then `kcadm.sh add-roles -r platform --uusername <name> --rolename ops-operator`, then set a temporary password with `kcadm.sh set-password -r platform --username <name> --temporary` (enter it at the prompt, not as an argument). *This sequence has not been exercised on the hosted instance yet: no operator exists. Test it with the first account.*
3. Hand over in person, with the authenticator app on the owner's phone ready. Do not send the password and the instruction together by message: whoever knows the password before the first login could enrol their own authenticator.

What the operator sees: `https://ops.<domain>/kafka/` redirects to the Keycloak login of realm `platform` (flow `ops-browser`: username and password, then a role check, then the one-time code). A user without the role `ops-operator` is refused after the password. First login of an operator: Keycloak shows a QR code to scan, asks for the first code, then opens Kafbat. Later logins ask for the code every time (there is no remembered-device or SSO-cookie shortcut). Kafbat then lists topics, partitions, offsets and consumer groups; there is no tab or link that shows a message, and every messages / produce / delete path is refused at the edge and by the Kafbat role. Brute-force: the edge allows about 10 login requests a minute per client; the realm locks an account for a while after repeated failures.

## 8. Master realm state and the console

Realm `master` holds the named admin only. State on 26-Oct: `bootstrap-admin` is absent; brute-force detection is on (5 failures, 60 s wait, 15 min maximum); the realm Frontend URL is unset; the named admin has a password but **no one-time code yet** (pending T220). The browser console is not reachable from the internet and an SSH tunnel does not complete its login by default (strict hostname sends the master login to the auth host, where it is 404 by design). Admin work therefore uses `kcadm.sh` in the container, as the scripts here do. Enrolling the admin's one-time code needs a controlled, time-boxed step (temporary Frontend URL over a tunnel, database dump first, cleared again afterwards); it is planned, not done.

## 9. Security status and known gaps

The Release 1 security statement is **partly executed, lane runs plus review**; it is not a security pass, and nothing here is a green light for external exposure. For the hosted instance:

| Item | Status |
|---|---|
| Keycloak console public with a single factor (H1) | Closed: no route to the console or master realm on any public host |
| Request splitting and double-encoding bypass on ops (N1, N2) | Closed: the ops edge refuses `%`, `;`, backslash, `//` and dot segments in the path |
| `Upgrade: h2c` hang on Keycloak (M1) | Closed: no `Upgrade`/`Connection` forwarded to Keycloak or Kafbat |
| Platform API audience on the `ops-kafbat` tokens (L5) | Removed in the setup; the token itself is not checked yet (needs a login) |
| Master admin one-time code | Open: T220, needs the owner at the PC |
| Edge literal blocks are case-sensitive (N4) | Open, Low (anonymous impact none); fix with the next edge change |
| Kafbat can reach a PLAINTEXT, ACL-less broker (M2) | Open: design for SASL/ACL and a separate Kafbat network |
| Kafka plaintext on the internal network (R1/F7) | Standing conditional High; per-environment probes P1-P9 open before any external exposure |
| No Traefik access log | Known gap: earlier hits on `/admin` from other addresses cannot be proven absent |

Also open: operator users do not exist yet (the end-to-end operator login is untested); `.env.kc-admin` sits in the stack directory and should move to the credential store; secret rotation is the owner's.

## 10. Open items

- T220: master admin one-time code (SSH tunnel, temporary Frontend URL, database dump first, about 20 minutes).
- Operator users for Kafbat; the first login enrols the one-time code.
- N4, M2 and the Kafka login layer (design: SASL/ACL for Kafbat, its own network).
- Move `.env.kc-admin` out of the stack directory into the credential store; rotation is the owner's.
- Traefik has no access log; enabling one would let future hits be proven.

## Related

[Hosted kit README](../deploy/hostinger/README.md) · [Architecture](architecture.md) (deployment section) · [ADR index](adr/README.md) · [Rules](rule.md)
