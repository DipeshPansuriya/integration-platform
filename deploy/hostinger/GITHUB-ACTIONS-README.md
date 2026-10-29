# GitHub Actions release automation (variant)

Pipeline: `.github/workflows/release.yml`.

## Triggers
- Pull requests and pushes to `main`: run Maven reactor and Gitleaks.
- Push to `main` and manual `workflow_dispatch`: additionally build/push images and deploy.

## Secrets (GitHub repository)
Set these in Settings -> Secrets and variables -> Actions:
`DOCKER_USERNAME`, `DOCKER_TOKEN`, `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `DB_PASSWORD`.

No secret is ever committed or echoed; workflow references them only via `${{ secrets.* }}`.

## Image tags
Images are `docker.io/<DOCKER_USERNAME>/ipass-platform-app:<short sha>` and `...:<full sha>`,
plus the matching `ipass-ui` tags. No `latest` tag is used.

## Deploy
The deploy job uses GitHub environment `Production Deploy` (configure protection/approvers there).
On the VPS, install `deploy/hostinger/github-deploy.sh` at `/docker/ipass/github-deploy.sh`
(chmod +x). It runs preflight, backup, two-line env swap, then `deploy.sh`.

## Unprivileged runtime
`platform-app` runs as UID/GID 10001 (`USER 10001` in the Dockerfile). Keep that unchanged.
