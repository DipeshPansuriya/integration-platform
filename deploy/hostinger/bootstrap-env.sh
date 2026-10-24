#!/bin/sh
# Generates every secret ON THIS MACHINE into a root-only .env and prints 8-character SHA-256 fingerprints only
# (house guide section 9). Never overwrites an existing file: a later rotation is one secret at a time, rotate-secret.sh.
# The temporary Keycloak bootstrap admin goes to its own file <env>.bootstrap-admin (mode 600), not into .env: it is used
# for the first start only (keycloak/compose.bootstrap-admin.yaml) and deleted by keycloak/retire-bootstrap-admin.sh.
#   IPASS_DOMAIN=example.org IMAGE_TAG=ab12cd3 ./bootstrap-env.sh [path-of-env-file]
# Output file: first argument, else ENV_FILE, else ./.env. Test hook: DRY_RUN=1 writes nothing, prints the secret names.
set -eu
umask 077
OUT="${1:-${ENV_FILE:-./.env}}"

SECRETS="POSTGRES_PASSWORD DB_MIGRATION_PASSWORD DB_PASSWORD BACKUP_DB_PASSWORD RESTORE_DB_PASSWORD KEYCLOAK_DB_PASSWORD
CONSOLE_OIDC_CLIENT_SECRET PLATFORM_ADMIN_CLIENT_SECRET OPERATOR_AGENT_CLIENT_SECRET
SHOWCASE_PASSWORD_ADMIN_A SHOWCASE_PASSWORD_CONFIGURATOR_A SHOWCASE_PASSWORD_APPROVER_A SHOWCASE_PASSWORD_ADMIN_B"

if [ "${DRY_RUN:-0}" = 1 ]; then
  for n in $SECRETS PLATFORM_CREDENTIAL_KEK_K1; do echo "$n"; done
  exit 0
fi

: "${IPASS_DOMAIN:?set IPASS_DOMAIN (placeholder until the domain is named)}"
: "${IMAGE_TAG:?set IMAGE_TAG (short commit of the merged main)}"
case "$IPASS_DOMAIN" in *[!A-Za-z0-9.-]*) echo "IPASS_DOMAIN has a character a hostname cannot hold" >&2; exit 2;; esac
case "$IMAGE_TAG" in latest) echo "IMAGE_TAG must be a commit tag, never latest" >&2; exit 2;; esac
case "$IMAGE_TAG" in *[!A-Za-z0-9._-]*) echo "IMAGE_TAG has a character a tag cannot hold" >&2; exit 2;; esac
if [ -e "$OUT" ] || [ -e "$OUT.bootstrap-admin" ]; then
  echo "$OUT exists - refusing to overwrite (rotate one secret with rotate-secret.sh; there is no blanket rotation: it would orphan the database roles, the realm and the sealed credentials)" >&2; exit 3
fi

# alphanumeric only: safe in compose interpolation, JDBC and psql without quoting
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40; }
fp() { printf '%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-8; }

tmp="$(mktemp "${OUT}.XXXXXX")"; tmpb="$(mktemp "${OUT}.XXXXXX")"
trap 'rm -f "$tmp" "$tmpb"' EXIT
{
  echo "IPASS_DOMAIN=$IPASS_DOMAIN"
  echo "IMAGE_TAG=$IMAGE_TAG"
} > "$tmp"
for n in $SECRETS; do
  v="$(rand)"
  echo "$n=$v" >> "$tmp"
  echo "$n fingerprint $(fp "$v")"
done
# KEK: 32 random bytes, base64 (config-secrets). Losing it makes sealed target credentials unreadable.
kek="$(openssl rand -base64 32)"
echo "PLATFORM_CREDENTIAL_KEK_K1=$kek" >> "$tmp"
echo "PLATFORM_CREDENTIAL_KEK_K1 fingerprint $(fp "$kek")"
kcb="$(rand)"
{ echo "KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin"; echo "KC_BOOTSTRAP_ADMIN_PASSWORD=$kcb"; } > "$tmpb"
echo "KC_BOOTSTRAP_ADMIN_PASSWORD fingerprint $(fp "$kcb") (file $OUT.bootstrap-admin)"
mv "$tmpb" "$OUT.bootstrap-admin"
mv "$tmp" "$OUT"
trap - EXIT
chmod 600 "$OUT" "$OUT.bootstrap-admin"
echo "wrote $OUT (mode 600). Keep your own copy of the KEK in the password manager; nothing else leaves this server."
