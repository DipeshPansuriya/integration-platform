# Releasing a new build to Hostinger - step by step

A practical runbook for putting the latest `main` on the Hostinger VPS (`ipass.dipeshpansuriya.tech`), including the database
migrations. It is the path that was used for release `7b080ea4` on 28-Oct-2026. The background and the full reference are in
[`README.md`](README.md) (sections "Migration pipeline" and "Update and roll back"); this file is the checklist.

No secret belongs in this file. Passwords live only in `/docker/ipass/.env` on the server.

---

## Before you start (one-time setup)

| What | How |
|---|---|
| SSH access | Key pair on your PC (`ssh-keygen -t ed25519 -f ~/.ssh/ipass_vps`), public key added in **hPanel → VPS → Settings → SSH keys** |
| SSH alias | `~/.ssh/config` entry below; test with `ssh ipass-vps "whoami"` (answers `root`) |
| Tools on your PC | Docker Desktop (running), JDK 25, Node/npm, Git Bash |

```
Host ipass-vps
    HostName 187.127.134.129
    User root
    IdentityFile ~/.ssh/ipass_vps
```

**Git Bash tip:** paste with **Shift+Insert** or right-click. Ctrl+V adds hidden `^[[200~` characters and the command fails with
`command not found`.

**Where to run what:** *PowerShell* and *Git Bash* = your PC, in `D:\Galaxy\integration-platform`. *Server* = after `ssh ipass-vps`,
in `/docker/ipass`.

---

## Step 0 - Get the release commit

1. Merge everything into `main` and push (GitHub Desktop).
2. Make sure nothing is uncommitted except notes/plans (Git Bash):
   ```bash
   git status -sb
   git rev-parse --short=8 HEAD
   ```
3. The 8-character commit id is the **release tag**. Below it is written as `<sc>` - replace it everywhere (e.g. `7b080ea4`).

> The migration image is built from the commit, not from the files on disk. An uncommitted file in a `db/migration` folder makes
> the build refuse.

---

## Step 1 - Build the code (PowerShell)

```powershell
cd D:\Galaxy\integration-platform
.\mvnw.cmd clean package -pl platform-app -am
cd ui; npm ci; npm run build; cd ..
```

**Check:**
- Maven ends with `BUILD SUCCESS`. On `BUILD FAILURE` stop and fix the failing test - do not skip tests to ship.
- `dir platform-app\target\*-exec.jar` shows **today's** time (an old jar means Maven did not run).
- The UI build ends with `built in ...` and the licence check line. The "chunks larger than 500 kB" warning is harmless.

---

## Step 2 - Build the Docker images

PowerShell:
```powershell
docker build -f platform-app/Dockerfile -t dipeshpansuriya/ipass-platform-app:<sc> .
docker build -f ui/Dockerfile           -t dipeshpansuriya/ipass-ui:<sc> .
```

Git Bash (migration image):
```bash
sh deploy/hostinger/flyway/build-image.sh <sc> ipass/flyway-migrate
```

**Check:**
```powershell
docker images --format "{{.Repository}}:{{.Tag}}  {{.Size}}" | findstr <sc>
```
Three lines: `ipass-platform-app` (~860 MB), `ipass-ui` (~100 MB), `ipass/flyway-migrate` (~860 MB). `build-image.sh` also prints
`OK: ... N migrations` - note N.

Keycloak is rebuilt only when `compose/keycloak/` or `deploy/hostinger/keycloak/` changed (see README); otherwise the current
Keycloak image stays.

---

## Step 3 - Copy the images to the server (Git Bash)

No Docker Hub login needed:
```bash
docker save dipeshpansuriya/ipass-platform-app:<sc> dipeshpansuriya/ipass-ui:<sc> ipass/flyway-migrate:<sc> | gzip | ssh ipass-vps 'gunzip | docker load'
```
Takes 5-15 minutes with no progress bar. **Check:** three `Loaded image: ...` lines at the end.

Nothing on the live site changes in steps 0-6.

---

## Step 4 - Back up (server)

```bash
ssh ipass-vps
cd /docker/ipass
mkdir -p /docker/ipass-backups && chmod 700 /docker/ipass-backups
cp -p .env            /docker/ipass-backups/env-before-<sc>
cp -p compose.yaml    /docker/ipass-backups/compose-before-<sc>.yaml
cp -p edge/edge.conf  /docker/ipass-backups/edge-before-<sc>.conf
docker exec ipass-postgres-1 sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > /docker/ipass-backups/db-before-<sc>.dump
ls -lh /docker/ipass-backups | grep <sc>
```
**Check:** the `.dump` file is not 0 bytes (it was ~600 KB in Oct 2026).

---

## Step 5 - Copy the deploy files of this commit (Git Bash on your PC)

Only needed when `deploy/hostinger/compose.yaml`, `edge/edge.conf`, `deploy.sh`, `flyway/` or an overlay changed since the
last release - check with `git diff <previous-sc>..<sc> --stat -- deploy/hostinger`.

```bash
git show <sc>:deploy/hostinger/compose.yaml   > /tmp/compose.yaml
git show <sc>:deploy/hostinger/edge/edge.conf > /tmp/edge.conf
scp /tmp/compose.yaml /tmp/edge.conf ipass-vps:/tmp/
ssh ipass-vps 'cp /tmp/compose.yaml /docker/ipass/compose.yaml && cp /tmp/edge.conf /docker/ipass/edge/edge.conf'
```
Copy `deploy.sh` / `flyway/*.sh` the same way if they changed, then `chmod +x` them on the server.

---

## Step 6 - Dry check of the migrations (server, read-only)

```bash
cd /docker/ipass
export IMAGE_TAG=<sc> FLYWAY_IMAGE=ipass/flyway-migrate:<sc>
./flyway/migrate.sh info
./flyway/migrate.sh validate
```
**Check - stop here if any of these is not true:**
- `info` lists only the migrations you expect as `Pending`, everything else `Success`.
- `validate` ends with `Successfully validated N migrations` (N = the number from step 2).

---

## Step 7 - Point the server at the new images (server - LIVE CHANGE)

`.env` pins the app and UI images by full name, and `deploy.sh` only rewrites `IMAGE_TAG`. Without this step the release would
migrate the database and then restart the **old** app.

```bash
sed -i 's#^IPASS_APP_IMAGE=.*#IPASS_APP_IMAGE=dipeshpansuriya/ipass-platform-app:<sc>#; s#^IPASS_UI_IMAGE=.*#IPASS_UI_IMAGE=dipeshpansuriya/ipass-ui:<sc>#' .env
grep -E '^IPASS_(APP|UI|KEYCLOAK)_IMAGE=' .env
```
**Check:** app and UI show `<sc>`; Keycloak is unchanged.

---

## Step 8 - Release (server - LIVE CHANGE, pick a quiet moment)

The live stack runs with **two overlays** (`demo` and `kafka-public`). They must be passed, or the restart drops the showcase
destination and the public Kafka settings.

```bash
COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml:kafka-public/compose.kafka-public.yaml \
IMAGE_TAG=<sc> FLYWAY_IMAGE=ipass/flyway-migrate:<sc> ./deploy.sh
echo "exit=$?"
```

What it does: backup → migrate (old app still serving) → switch tags → start → wait for health → show migration history.
The site is down or slow for 1-3 minutes. Do not edit anything in the console while it runs.

**Check:** it ends with `=== Release <sc> complete ...` and `exit=0`. Any other exit code: it prints rollback steps - see
"Roll back" below.

To confirm the overlays are still the ones in use:
```bash
docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' ipass-platform-app-1
```

---

## Step 9 - Verify

Server:
```bash
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' | grep '^ipass-'
./flyway/migrate.sh info | tail -8
docker logs --since 5m ipass-platform-app-1 2>&1 | grep -c '"level":"ERROR"'
exit
```
Your PC:
```bash
curl -s https://ipass.dipeshpansuriya.tech/actuator/health
```

**Check:**
- `platform-app`, `ui` and `edge` run `<sc>` and are `(healthy)`.
- All migrations `Success`; `Schema version` is the newest one.
- 0 ERROR lines in the app log.
- Health is `{"...","status":"UP"}`.
- In the browser: log in at `https://ipass.dipeshpansuriya.tech`, open Systems and Flows, save one small change.

---

## Roll back

On the server, in `/docker/ipass` (`deploy.sh` prints the exact folder name of its own backup):
```bash
cp -p release-backups/<timestamp>/.env.bak .env
cp -p release-backups/<timestamp>/compose.yaml.bak compose.yaml
COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml:kafka-public/compose.kafka-public.yaml docker compose up -d
```
This brings the previous app back. The **database schema stays new**. If the old app cannot work with it, the database must be
restored from `/docker/ipass-backups/db-before-<sc>.dump` - a careful step with downtime; follow README section "Migration
pipeline", paragraph "If a migration fails" (stop the app, drop and re-create the `ipass` database, `pg_restore` the dump as the
superuser) and do not improvise it. If the same step fails twice, stop and find out why before a third try.

---

## Other everyday changes

### Allow a new destination host
A save refused with *"settings.baseUrl is refused: the host is not on the destination allow-list"*.

The Destinations screen is operator-only (PR #90), so no console user can open it. Use the server setting instead (server):
```bash
cd /docker/ipass
cp -p .env /docker/ipass-backups/env-before-desthosts-$(date -u +%Y%m%d)
grep IPASS_DESTINATION_HOSTS .env                 # see the current list
# edit that ONE line (nano .env) - keep every existing host, add the new one, comma-separated, e.g.
# IPASS_DESTINATION_HOSTS=wiremock,bialuat.cargobyblr.in,new.host.example
COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml:kafka-public/compose.kafka-public.yaml docker compose up -d platform-app
docker exec ipass-platform-app-1 env | grep ALLOWEDHOSTS
```
- **Always keep `wiremock`** - the demo systems need it.
- Add exact hosts; avoid wildcards like `*.example.com` unless every subdomain is meant to be reachable.
- About 1 minute of downtime (the list is read at start-up).

### Only `edge/edge.conf` changed
It is a single-file bind mount, a restart keeps the old file - force-recreate:
```bash
COMPOSE_FILE=compose.yaml:demo/compose.demo.yaml:kafka-public/compose.kafka-public.yaml docker compose up -d --force-recreate --no-deps edge
```

---

## Things that went wrong before (and what to do)

| Symptom | Cause | Fix |
|---|---|---|
| `bash: $'\E[200~sh': command not found` | Ctrl+V paste in Git Bash | Paste with Shift+Insert or type the command |
| Maven `BUILD FAILURE`, `There are test failures` | A real failing test | Read `<module>/target/surefire-reports`, fix, commit, new `<sc>` |
| Jar in `platform-app/target` has an old date | Maven step did not run or failed | Run step 1 again, check `BUILD SUCCESS` |
| After release the old version is still running | `.env` image lines not updated | Step 7, then `docker compose up -d` with the overlays |
| Demo systems (wiremock) refused after a restart | Overlays missing, or `wiremock` dropped from `IPASS_DESTINATION_HOSTS` | Use the `COMPOSE_FILE=...` line; keep `wiremock` in the list |
| App cannot connect to the public database `187.127.134.129:5432` | That port is `ipass_demo`, a read-only snapshot for outside viewers, not the live database | The live database is only reachable on the server (`docker exec ipass-postgres-1 ...`) |

---

## Release log

| Date (UTC) | Tag | Migrations applied | By | Notes |
|---|---|---|---|---|
| 2026-10-28 10:16 | `7b080ea4` | V2_4_1490, V2_4_1500, V2_4_1510 | Vasu | First run of `deploy.sh` on this server; `IPASS_DESTINATION_HOSTS` set to `wiremock,bialuat.cargobyblr.in` afterwards |
| 2026-10-29 13:46 | `c9fed065` | V2_4_1550 (V2_4_1530 + V2_4_1540 were already applied 08:01 UTC by a manual migrate run) | Vasu | Console redesign (W6) + Delivered/search endpoints + tenant Destinations (risk accepted: edits the global allow-list). New `compose.yaml`: app runs with `SPRING_FLYWAY_ENABLED=false` (row 570 M1). `rotate-secret.sh` on the server not updated (older copy kept) |
