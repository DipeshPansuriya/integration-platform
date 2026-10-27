#!/bin/sh
# Self-signed server certificate for the outside Postgres door (ECDSA P-256, default 365 days), generated ON THE SERVER.
#   ./outside/make-tls-cert.sh <dir> <san,san,...>      e.g. ./outside/make-tls-cert.sh ./outside/tls 203.0.113.7,db.example.org
# SAN entries are IPs or host names; the first one becomes the CN. Never overwrites. The private key never leaves the dir.
# The PUBLIC server.crt is what clients pin (sslrootcert=server.crt, sslmode=verify-full); it is not a secret.
set -eu
umask 077
export MSYS_NO_PATHCONV=1
dir="${1:?usage: make-tls-cert.sh <dir> <san,san,...>}"; sans="${2:?list at least one IP or host name}"
[ ! -e "$dir/server.key" ] || { echo "$dir/server.key exists - refusing to overwrite (delete both files first to renew)" >&2; exit 3; }
san=""; first=""
oldifs="$IFS"; IFS=,
for s in $sans; do
  case "$s" in ""|*[!A-Za-z0-9.:-]*) echo "bad SAN entry" >&2; exit 2;; esac
  case "$s" in *[!0-9.]*) k=DNS;; *) k=IP;; esac
  san="${san:+$san,}$k:$s"; first="${first:-$s}"
done
IFS="$oldifs"
mkdir -p "$dir"
# relative file names inside $dir: a native Windows openssl cannot read POSIX temp paths, and -subj must not be path-converted
( cd "$dir" && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "${DAYS:-365}" \
  -subj "/CN=$first" -addext "subjectAltName=$san" -keyout server.key -out server.crt )
chmod 600 "$dir/server.key"; chmod 644 "$dir/server.crt"
# the postgres user of the alpine image is uid 70; a key owned by anyone else, or readable by others, is refused by postgres
chown 70:70 "$dir/server.key" "$dir/server.crt" 2>/dev/null || echo "note: could not chown to 70:70 - run as root on the server" >&2
echo "wrote $dir/server.crt and server.key (SAN: $san)"
( cd "$dir" && openssl x509 -noout -fingerprint -sha256 -in server.crt )
